package me.serce.solidity.chain.wallet

import me.serce.solidity.util.checksumAddress
import me.serce.solidity.util.isAddress
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import me.serce.solidity.chain.WalletConfig
import me.serce.solidity.chain.WalletKind
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JComponent

/** The wallets of a network chain, edited in place in [wallets]. */
class WalletsEditor(private val project: Project, private val wallets: MutableList<WalletConfig>) {
  private val model = CollectionListModel(wallets.map { it.copy() })
  private val list = JBList(model).apply {
    cellRenderer = SimpleListCellRenderer.create("") { w -> "${w.address}   ${w.label}" }
    emptyText.text = "No wallets: only calls, no transactions"
  }

  val component: JComponent = ToolbarDecorator.createDecorator(list)
    .setAddAction { button ->
      val step = object : BaseListPopupStep<WalletKind>("Add Wallet", WalletKind.entries) {
        override fun getTextFor(value: WalletKind) = value.title
        override fun onChosen(selectedValue: WalletKind, finalChoice: Boolean): PopupStep<*>? {
          doFinalStep { add(selectedValue) }
          return FINAL_CHOICE
        }
      }
      JBPopupFactory.getInstance().createListPopup(step).show(button.preferredPopupPoint)
    }
    .disableUpDownActions()
    .createPanel()

  private fun add(kind: WalletKind) {
    val dialog = AddWalletDialog(project, kind)
    if (!dialog.showAndGet()) return
    val wallet = dialog.wallet
    model.items.indexOfFirst { it.address.equals(wallet.address, ignoreCase = true) }.takeIf { it >= 0 }?.let { model.remove(it) }
    model.add(wallet)
  }

  /** Writes the list back into the chain's wallets. */
  fun apply() {
    wallets.clear()
    wallets += model.items
  }
}

/**
 * Adds one wallet. Keystores and hardware wallets get their address from cast (a keystore asks for its password, which
 * can be remembered in the system keychain); a browser wallet's address is typed, since the browser only tells cast at signing.
 */
class AddWalletDialog(private val project: Project, private val kind: WalletKind) : DialogWrapper(project) {
  val wallet = WalletConfig().also { it.kind = kind }
  private val keystoreModel = CollectionComboBoxModel(CastWallet.defaultKeystores().toMutableList())
  private val keystoreCombo = ComboBox(keystoreModel).apply {
    renderer = SimpleListCellRenderer.create("") { f -> f?.let { if (it.parentFile == CastWallet.defaultKeystoreDir) it.name else it.path } ?: "" }
  }
  private val pathField = JBTextField()
  private val addressField = JBTextField()

  init {
    title = "Add ${kind.title}"
    setOKButtonText("Add")
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    when (kind) {
      WalletKind.KEYSTORE -> {
        row("Keystore:") {
          cell(keystoreCombo).align(AlignX.FILL).resizableColumn()
          button("Browse…") {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Choose Keystore"), project, null)?.let { vf ->
              val file = File(vf.path)
              keystoreModel.add(file)
              keystoreModel.selectedItem = file
            }
          }
        }.rowComment("Keystores in ~/.foundry/keystores are listed. The plugin never sees the private key: cast decrypts the keystore when signing.")
        row {
          val command = "cast wallet import <name> --interactive"
          text("To add a key, run <code>$command</code> in a terminal, then reopen this dialog. Type the key into cast, never into the IDE.")
          button("Copy Command") { CopyPasteManagerEx.getInstance().setContents(StringSelection(command)) }
        }
      }
      WalletKind.LEDGER, WalletKind.TREZOR -> {
        row("Derivation path:") {
          cell(pathField).align(AlignX.FILL).comment(
            "Empty for the first account. Ledger Live's second account is m/44'/60'/1'/0/0. " +
              "Connect and unlock the device${if (kind == WalletKind.LEDGER) " and open its Ethereum app" else ""}: Add reads the address from it."
          )
        }
      }
      WalletKind.BROWSER -> {
        row("Address:") {
          cell(addressField).align(AlignX.FILL).focused().comment(
            "The account you will use in the browser wallet (MetaMask, Rabby, …). For each transaction cast opens a page " +
              "in your browser where you approve it in the wallet, which sends it."
          )
        }
      }
    }
  }

  override fun doValidate(): ValidationInfo? = when (kind) {
    WalletKind.KEYSTORE -> if (keystoreCombo.selectedItem == null) ValidationInfo("Pick a keystore", keystoreCombo) else null
    WalletKind.BROWSER -> {
      if (!isAddress(addressField.text)) ValidationInfo("Not an address", addressField) else null
    }
    else -> null
  }

  override fun doOKAction() {
    when (kind) {
      WalletKind.BROWSER -> wallet.address = checksumAddress(addressField.text.trim())
      WalletKind.KEYSTORE -> {
        wallet.keystorePath = (keystoreCombo.selectedItem as File).path
        if (!detectAddress()) return
      }
      WalletKind.LEDGER, WalletKind.TREZOR -> {
        wallet.derivationPath = pathField.text.trim()
        if (!detectAddress()) return
      }
    }
    super.doOKAction()
  }

  /** Asks cast for the address; shows why it failed and keeps the dialog open when it does. */
  private fun detectAddress(): Boolean {
    val cast = CastWallet(CastWallet.castPath(project))
    var error: String? = null
    while (true) {
      val password = if (kind == WalletKind.KEYSTORE) DefaultWalletPrompts.password(project, wallet, error) ?: return false else null
      var failure: CastException? = null
      val address = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>({
        try {
          cast.address(wallet, password)
        } catch (e: CastException) {
          failure = e
          null
        }
      }, if (kind == WalletKind.KEYSTORE) "Reading keystore" else "Reading address from ${kind.title}", true, project)
      if (address != null) {
        wallet.address = address
        return true
      }
      val f = failure ?: return false
      if (f.wrongPassword) {
        error = "Wrong password"
        continue
      }
      setErrorText(f.message)
      return false
    }
  }
}
