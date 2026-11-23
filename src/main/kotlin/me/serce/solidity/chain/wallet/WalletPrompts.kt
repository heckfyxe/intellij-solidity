package me.serce.solidity.chain.wallet

import com.intellij.credentialStore.askPassword
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import me.serce.solidity.chain.Secrets
import me.serce.solidity.chain.WalletConfig
import me.serce.solidity.chain.abi.AbiValueFormatter
import me.serce.solidity.chain.ui.ResultView
import java.io.File
import java.math.BigInteger
import javax.swing.JCheckBox
import javax.swing.JComponent

/** What the user is asked to approve before a transaction is signed for a real network. */
class TxSummary(
  val chainName: String,
  val chainId: Long?,
  val mainnet: Boolean,
  val from: String,
  val wallet: String,
  /** The contract called, e.g. `Vault 0x12…ab`, or null for a deploy. */
  val to: String?,
  /** e.g. `deposit(uint256)` or `deploy Vault`. */
  val action: String,
  val args: List<String>,
  val value: BigInteger,
  /** Estimated gas times the current gas price; null when the node couldn't say. */
  val estimatedFee: BigInteger?,
)

/** Asks the user things while a transaction is being sent. Called off the EDT; blocks until answered. */
interface WalletPrompts {
  /** [Confirmation.SEND_AND_STOP_ASKING] only for test networks, when the user ticked "don't ask again". */
  fun confirm(project: Project, summary: TxSummary): Confirmation

  /** The keystore password, from the password safe when the user chose to remember it; null when cancelled. */
  fun password(project: Project, wallet: WalletConfig, error: String?): String?

  enum class Confirmation { SEND, SEND_AND_STOP_ASKING, CANCEL }
}

object DefaultWalletPrompts : WalletPrompts {
  override fun confirm(project: Project, summary: TxSummary): WalletPrompts.Confirmation {
    var result = WalletPrompts.Confirmation.CANCEL
    ApplicationManager.getApplication().invokeAndWait({
      val dialog = TxConfirmDialog(project, summary)
      if (dialog.showAndGet()) {
        result = if (dialog.stopAsking) WalletPrompts.Confirmation.SEND_AND_STOP_ASKING else WalletPrompts.Confirmation.SEND
      }
    }, ModalityState.any())
    return result
  }

  override fun password(project: Project, wallet: WalletConfig, error: String?): String? =
    askPassword(
      project, "Keystore Password", "Password for ${File(wallet.keystorePath).name}:",
      Secrets.attributes(Secrets.keystorePasswordKey(wallet.keystorePath)),
      resetPassword = error != null, error = error,
    )
}

/** Shows exactly what will be signed. Mainnets always ask; test networks can stop asking until the IDE restarts. */
class TxConfirmDialog(project: Project, private val summary: TxSummary) : DialogWrapper(project) {
  private val stopAskingBox = JCheckBox("Don't ask again for ${summary.chainName} until the IDE restarts")
  val stopAsking get() = !summary.mainnet && stopAskingBox.isSelected

  init {
    title = if (summary.mainnet) "Send Transaction on ${summary.chainName} (Real Funds)" else "Send Transaction on ${summary.chainName}"
    setOKButtonText(if (summary.to == null) "Sign and Deploy" else "Sign and Send")
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    if (summary.mainnet) {
      row {
        label(ResultView.html("<b style='color:${ResultView.red()}'>This is a real network: the value and fees are paid with real funds.</b>"))
      }
    }
    row("Network:") { label("${summary.chainName}  (chain id ${summary.chainId ?: "unknown"})") }
    row("From:") { label(ResultView.html("<code>${summary.from}</code>  ${ResultView.esc(summary.wallet)}")) }
    row("To:") { label(ResultView.html(summary.to?.let { ResultView.esc(it) } ?: "a new contract")) }
    row("Action:") { label(ResultView.html("<code>${ResultView.esc(summary.action)}</code>")) }
    for (arg in summary.args) row("") { label(ResultView.html("<code>${ResultView.esc(arg)}</code>")) }
    row("Value:") { label(AbiValueFormatter.ether(summary.value)) }
    row("Estimated fee:") { label(summary.estimatedFee?.let { "≈ ${AbiValueFormatter.ether(it, decimals = 8)}" } ?: "unknown") }
    if (!summary.mainnet) row { cell(stopAskingBox) }
  }.apply { border = JBUI.Borders.empty(4) }
}
