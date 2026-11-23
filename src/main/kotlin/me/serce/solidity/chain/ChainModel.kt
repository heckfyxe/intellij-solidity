package me.serce.solidity.chain

import me.serce.solidity.chain.abi.AbiEntry
import me.serce.solidity.chain.abi.ContractAbi
import me.serce.solidity.chain.debug.FailedFrame
import java.util.UUID

/**
 * [ANVIL]: a local chain we start. [EXTERNAL]: a node you run, whose accounts it unlocks (e.g. anvil in a terminal).
 * [NETWORK]: a real network such as Sepolia or Ethereum Mainnet; transactions are signed by [WalletConfig]s.
 */
enum class ChainKind { ANVIL, EXTERNAL, NETWORK }

/** A saved chain setup. Mutable bean so the platform can serialize it to workspace.xml. */
class ChainConfig {
  var id: String = UUID.randomUUID().toString()
  var name: String = "Local chain"
  var kind: ChainKind = ChainKind.ANVIL
  /** For [ChainKind.ANVIL]: fork this RPC when not blank. */
  var forkUrl: String = ""
  var forkBlock: String = ""
  var chainId: String = ""
  var port: Int = 8545
  var accounts: Int = 10
  var balanceEth: Int = 10000
  /** Keep anvil state, deployments and history between runs. */
  var persistent: Boolean = true
  /** For [ChainKind.EXTERNAL]. */
  var url: String = "http://127.0.0.1:8545"
  /** For [ChainKind.NETWORK]: the name of an entry in [Networks]; its URL may hold an API key, so it is kept there. */
  var network: String = ""
  /**
   * For [ChainKind.NETWORK]: the chain id the RPC reported when first connected. A different id later (the network's
   * URL was edited to point elsewhere) refuses to connect, so nothing is signed for a chain the user didn't pick.
   */
  var expectedChainId: String = ""
  /** For [ChainKind.NETWORK]: accounts that sign transactions. */
  var wallets: MutableList<WalletConfig> = ArrayList()

  // Not `isNetwork`: XmlSerializer takes `isNetwork()` for a getter of the `network` property and, depending on the
  // order the JVM lists methods in, then doesn't save the String `network` (a network chain came back with no network
  // after reopening the project). @Transient on it doesn't help: it can make the whole `network` property transient.
  val isFork get() = kind == ChainKind.ANVIL && forkUrl.isNotBlank()
  val isNetworkChain get() = kind == ChainKind.NETWORK

  /** Identifies what anvil's saved state depends on: loading it on a different fork or chain id breaks the chain. */
  val stateKey: String get() = Integer.toHexString("${forkUrl.trim().trimEnd('/')}|${forkBlock.trim()}|${chainId.trim()}".hashCode())

  fun copy(): ChainConfig = ChainConfig().also {
    it.id = id; it.name = name; it.kind = kind; it.forkUrl = forkUrl; it.forkBlock = forkBlock; it.chainId = chainId
    it.port = port; it.accounts = accounts; it.balanceEth = balanceEth; it.persistent = persistent; it.url = url
    it.network = network; it.expectedChainId = expectedChainId; it.wallets = wallets.mapTo(ArrayList()) { w -> w.copy() }
  }

  val description: String
    get() = when {
      kind == ChainKind.EXTERNAL -> url
      kind == ChainKind.NETWORK -> network
      isFork -> "fork of ${Networks.getInstance().nameOf(forkUrl) ?: forkUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')}" +
        (if (forkBlock.isNotBlank()) " @ block $forkBlock" else "")
      else -> "anvil"
    }
}

enum class WalletKind(val title: String) {
  KEYSTORE("Foundry keystore"), LEDGER("Ledger"), TREZOR("Trezor"), BROWSER("Browser wallet");
}

/**
 * An account that signs transactions on a [ChainKind.NETWORK] chain. Signing is done by `cast`; the plugin keeps no
 * key material, only where the key is (a keystore file, a hardware wallet's derivation path) and its address.
 */
class WalletConfig {
  var kind: WalletKind = WalletKind.KEYSTORE
  var address: String = ""
  /** For [WalletKind.KEYSTORE]: the keystore file. */
  var keystorePath: String = ""
  /** For [WalletKind.LEDGER] and [WalletKind.TREZOR]: e.g. `m/44'/60'/1'/0/0`; empty for cast's default (the first account). */
  var derivationPath: String = ""

  val label: String
    get() = when (kind) {
      WalletKind.KEYSTORE -> "keystore ${java.io.File(keystorePath).name}"
      WalletKind.LEDGER, WalletKind.TREZOR -> kind.title + (derivationPath.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
      WalletKind.BROWSER -> kind.title
    }

  fun copy(): WalletConfig = WalletConfig().also { it.kind = kind; it.address = address; it.keystorePath = keystorePath; it.derivationPath = derivationPath }
}

/** A contract instance on a chain, deployed by us or attached by address. */
class Deployment {
  var id: String = UUID.randomUUID().toString()
  var chainId: String = ""
  var name: String = ""
  /** Relative to the Foundry root; empty when unknown. */
  var sourcePath: String = ""
  var address: String = ""
  var abiJson: String = "[]"
  /** Where the ABI came from when there is no source, e.g. "pasted", "Sourcify", "Etherscan"; empty for compiled contracts. */
  var abiSource: String = ""

  @Transient
  private var parsedAbi: ContractAbi? = null

  fun abi(): ContractAbi = parsedAbi ?: ContractAbi.parse(abiJson).also { parsedAbi = it }

  @Transient
  private var sorted: List<AbiEntry.Function>? = null

  /** Read-only functions first, then by name; empty when the ABI can't be parsed. */
  fun sortedFunctions(): List<AbiEntry.Function> = sorted ?: runCatching { abi().functions }.getOrDefault(emptyList())
    .sortedWith(compareBy({ !it.mutability.isReadOnly }, { it.name })).also { sorted = it }
}

/** A contract on the way to a failure, and the source line it was at when it failed (path empty when unknown). */
class ErrorFrame {
  var contract: String = ""
  var address: String = ""
  var path: String = ""
  /** Zero-based. */
  var line: Int = 0
  var column: Int = 0
  var code: String = ""

  /** `src/A.sol:12` with a 1-based line, the way compilers and the terminal print it. */
  val label: String get() = "$path:${line + 1}"

  companion object {
    fun of(frame: FailedFrame) = ErrorFrame().apply {
      contract = frame.contract.orEmpty()
      address = frame.address.orEmpty()
      frame.location?.let { path = it.path; line = it.line; column = it.column; code = it.code }
    }
  }
}

enum class TxKind { DEPLOY, CALL, TRANSACT }
enum class TxStatus { SUCCESS, REVERTED, FAILED }

class TxRecord {
  var id: String = UUID.randomUUID().toString()
  var chainId: String = ""
  var timestamp: Long = System.currentTimeMillis()
  var kind: TxKind = TxKind.CALL
  var status: TxStatus = TxStatus.SUCCESS
  var contractName: String = ""
  var sourcePath: String = ""
  /** e.g. `fund()` or `constructor(uint256)`. */
  var function: String = ""
  var from: String = ""
  var to: String = ""
  var valueWei: String = "0"
  /** Formatted arguments, one per line `name: value`. */
  var args: MutableList<String> = ArrayList()
  /** Formatted return values, one per line. */
  var results: MutableList<String> = ArrayList()
  var events: MutableList<String> = ArrayList()
  var error: String = ""
  /** Where it failed first, then each caller up to the called contract; empty when the node can't trace. */
  var errorFrames: MutableList<ErrorFrame> = ArrayList()
  /** What else went wrong on the way, e.g. calls to addresses without code. */
  var notes: MutableList<String> = ArrayList()
  var txHash: String = ""
  var blockNumber: String = ""
  var gasUsed: String = ""

  val title: String get() = if (kind == TxKind.DEPLOY) "deploy $contractName" else "$contractName.$function"
}

class ChainState {
  var chains: MutableList<ChainConfig> = ArrayList()
  var deployments: MutableList<Deployment> = ArrayList()
  var history: MutableList<TxRecord> = ArrayList()
  var selectedAccount: MutableMap<String, String> = HashMap()
  var compileWhileEditing: Boolean = true
}
