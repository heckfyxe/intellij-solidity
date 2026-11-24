package me.serce.solidity.chain.wallet

import me.serce.solidity.util.toHex
import me.serce.solidity.util.checksumAddress
import me.serce.solidity.util.ADDRESS_REGEX
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import me.serce.solidity.chain.WalletConfig
import me.serce.solidity.chain.WalletKind
import me.serce.solidity.chain.foundry.FoundryProject
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class CastException(message: String, val wrongPassword: Boolean = false) : Exception(message)

/** An unsigned transaction: [to] is null for a deploy, whose [data] is the init code. */
class TxRequest(val to: String?, val data: ByteArray, val value: BigInteger)

/**
 * Signs with Foundry's `cast`, so key material never enters the IDE: keystores are decrypted by cast, hardware wallets
 * sign on the device, and browser wallets sign in the browser. Every call blocks; run it off the EDT.
 *
 * The RPC URL goes to cast in `ETH_RPC_URL`, not on the command line, where any user could read an API key in it
 * with `ps`. A keystore password goes in a file only the user can read, deleted as soon as cast exits (cast reads
 * passwords from a terminal, a file, or a command-line argument; `ETH_PASSWORD` names a file too).
 */
class CastWallet(private val cast: String) {

  /** The wallet's address. A keystore needs [password]; a hardware wallet must be connected and unlocked. */
  fun address(wallet: WalletConfig, password: String?): String {
    val out = withPassword(password) { passwordFile ->
      run(listOf(cast, "wallet", "address") + walletArgs(wallet, passwordFile), env = emptyMap(), timeout = timeoutFor(wallet))
    }
    val address = ADDRESS_REGEX.find(out)?.value ?: throw CastException("cast printed no address: ${out.trim().take(200)}")
    return checksumAddress(address)
  }

  /** Signs [tx] for the chain at [rpcUrl] and returns the raw transaction for `eth_sendRawTransaction`. */
  fun sign(rpcUrl: String, wallet: WalletConfig, password: String?, tx: TxRequest): String {
    require(wallet.kind != WalletKind.BROWSER) { "Browser wallets send transactions themselves" }
    val out = withPassword(password) { passwordFile ->
      run(listOf(cast, "mktx") + walletArgs(wallet, passwordFile) + txArgs(tx), mapOf("ETH_RPC_URL" to rpcUrl), timeoutFor(wallet))
    }
    return RAW_TX.findAll(out).lastOrNull()?.value ?: throw CastException("cast printed no transaction: ${out.trim().take(200)}")
  }

  /**
   * Opens the browser wallet page that cast serves, where the user approves [tx] in their wallet, which sends it.
   * Returns the transaction hash.
   */
  fun sendWithBrowser(rpcUrl: String, wallet: WalletConfig, tx: TxRequest): String {
    val out = run(
      listOf(cast, "send", "--async") + walletArgs(wallet, null) + txArgs(tx),
      mapOf("ETH_RPC_URL" to rpcUrl), timeoutFor(wallet),
    )
    return TX_HASH.findAll(out).lastOrNull()?.value ?: throw CastException("cast printed no transaction hash: ${out.trim().take(200)}")
  }

  private fun walletArgs(wallet: WalletConfig, passwordFile: Path?): List<String> = when (wallet.kind) {
    WalletKind.KEYSTORE -> listOf("--keystore", wallet.keystorePath) + (passwordFile?.let { listOf("--password-file", it.toString()) } ?: emptyList())
    WalletKind.LEDGER -> listOf("--ledger") + derivation(wallet)
    WalletKind.TREZOR -> listOf("--trezor") + derivation(wallet)
    WalletKind.BROWSER -> listOf("--browser") + (wallet.address.takeIf { it.isNotEmpty() }?.let { listOf("--from", it) } ?: emptyList())
  }

  private fun derivation(wallet: WalletConfig) =
    wallet.derivationPath.trim().takeIf { it.isNotEmpty() }?.let { listOf("--mnemonic-derivation-path", it) } ?: emptyList()

  /** Options first: `--create` starts a subcommand that takes no more of them. */
  private fun txArgs(tx: TxRequest): List<String> {
    val value = listOf("--value", tx.value.toString())
    return if (tx.to == null) value + listOf("--create", tx.data.toHex()) else value + listOf(tx.to, tx.data.toHex())
  }

  private fun timeoutFor(wallet: WalletConfig): Long = when (wallet.kind) {
    WalletKind.KEYSTORE -> 60
    WalletKind.LEDGER, WalletKind.TREZOR -> 180 // the user confirms on the device
    WalletKind.BROWSER -> 600 // the user switches to the browser and approves there
  }

  private fun <T> withPassword(password: String?, body: (Path?) -> T): T {
    if (password == null) return body(null)
    val file = writePasswordFile(password)
    try {
      return body(file)
    } finally {
      deletePasswordFile(file)
    }
  }

  private fun run(command: List<String>, env: Map<String, String>, timeout: Long): String {
    if (!File(cast).canExecute()) throw CastException("cast not found at $cast. Install Foundry with foundryup.")
    // cast must not pick up the user's own wallet settings. GeneralCommandLine merges the parent environment only when the
    // process starts, so removing them from its `environment` would not work: pass the whole environment instead.
    val cmd = GeneralCommandLine(command).withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
      .withEnvironment(EnvironmentUtil.getEnvironmentMap() - WALLET_ENV.toSet() + env + ("NO_COLOR" to "1"))
    val handler = CapturingProcessHandler(cmd)
    val indicator: ProgressIndicator? = ProgressManager.getInstance().progressIndicator
    val output = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, TimeUnit.SECONDS.toMillis(timeout).toInt())
    else handler.runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt())
    if (output.isCancelled) throw CastException("Cancelled")
    if (output.isTimeout) throw CastException("cast did not finish within $timeout seconds")
    if (output.exitCode != 0) {
      val text = (output.stderr + "\n" + output.stdout).trim()
      throw CastException(errorMessage(text), wrongPassword = text.contains("Mac Mismatch") || text.contains("Failed to decrypt"))
    }
    return output.stdout
  }

  companion object {
    private val RAW_TX = Regex("0x[0-9a-fA-F]{100,}")
    private val TX_HASH = Regex("0x[0-9a-fA-F]{64}\\b")
    /** Variables that would make cast or forge sign with something other than the wallet we pass. */
    val WALLET_ENV = listOf("ETH_PASSWORD", "ETH_KEYSTORE", "ETH_KEYSTORE_ACCOUNT", "ETH_FROM", "ETH_PRIVATE_KEY", "ETH_MNEMONIC", "CAST_ASYNC")

    /**
     * Writes [password] to a file only the user can read, in a directory only the user can open: cast and forge read
     * keystore passwords from a file (FIFOs are rejected) or the command line, which `ps` shows. Delete it with
     * [deletePasswordFile] as soon as the process exits.
     */
    fun writePasswordFile(password: String): Path {
      val dir = if (SystemInfo.isUnix) Files.createTempDirectory("sol-cast", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      else Files.createTempDirectory("sol-cast")
      val file = dir.resolve("p")
      if (SystemInfo.isUnix) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      Files.writeString(file, password)
      return file
    }

    fun deletePasswordFile(file: Path) {
      Files.deleteIfExists(file)
      Files.deleteIfExists(file.parent)
    }

    fun castPath(project: Project): String = FoundryProject.toolPath(project, "cast")

    /** cast's own `Error: …` line and its context, without the noise around it. */
    fun errorMessage(output: String): String {
      val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
      val start = lines.indexOfFirst { it.startsWith("Error:") }
      val relevant = if (start >= 0) lines.drop(start) else lines.takeLast(3)
      return relevant.filter { it != "Context:" }.joinToString(" ") { it.removePrefix("Error:").removePrefix("- ").trim() }.take(500)
        .ifEmpty { "cast failed" }
    }

    /** Foundry's default keystore folder, where `cast wallet import` puts keys. */
    val defaultKeystoreDir = File(System.getProperty("user.home"), ".foundry/keystores")

    /** The keystores in [defaultKeystoreDir]. */
    fun defaultKeystores(): List<File> =
      defaultKeystoreDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }
  }
}
