package me.serce.solidity.chain.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainKind
import me.serce.solidity.chain.Network
import me.serce.solidity.chain.Networks
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.foundry.Artifact
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

private val URL_REGEX = Regex("^(https?|wss?)://.+")

/**
 * Add or edit a chain. A local chain is an anvil started by the IDE, optionally forking one of the
 * saved networks; the other kind connects to a node that is already running at some URL.
 */
class ChainDialog(private val project: Project, private val config: ChainConfig, isNew: Boolean) : DialogWrapper(project) {
  private lateinit var urlField: JTextField
  private lateinit var nameField: JTextField
  private lateinit var forkBlockField: JTextField
  private val forkModel = CollectionComboBoxModel<Network?>()
  private val forkCombo = ComboBox(forkModel)
  private var nameEditedByUser = !isNew
  private var settingName = false

  init {
    title = when {
      !isNew -> "Edit ${config.name}"
      config.kind == ChainKind.EXTERNAL -> "Connect to Node"
      else -> "Add Local Chain"
    }
    forkCombo.renderer = SimpleListCellRenderer.create("") { n ->
      if (n == null) "None: start an empty chain" else "${n.name}   ${n.url}"
    }
    reloadNetworks(selectUrl = config.forkUrl)
    forkCombo.addActionListener { onForkChanged() }
    init()
  }

  private val selectedFork: Network? get() = forkCombo.selectedItem as Network?

  private fun reloadNetworks(selectUrl: String) {
    val networks = Networks.getInstance().list.toMutableList<Network?>()
    // a fork URL from an older setup that is not in the list any more stays selectable
    if (selectUrl.isNotBlank() && networks.none { it != null && Network.sameUrl(it.url, selectUrl) }) {
      networks += Network(selectUrl, selectUrl)
    }
    forkModel.replaceAll(listOf<Network?>(null) + networks)
    forkModel.selectedItem = networks.firstOrNull { it != null && Network.sameUrl(it.url, selectUrl) }
  }

  private fun onForkChanged() {
    if (::forkBlockField.isInitialized) forkBlockField.isEnabled = selectedFork != null
    if (!nameEditedByUser && ::nameField.isInitialized) {
      settingName = true
      nameField.text = selectedFork?.let { "${it.name} fork" } ?: "Local chain"
      settingName = false
    }
  }

  override fun createCenterPanel(): JComponent = panel {
    row("Name:") {
      nameField = textField().bindText(config::name).align(AlignX.FILL).focused().component
      nameField.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          if (!settingName) nameEditedByUser = true
        }
      })
    }
    if (config.kind == ChainKind.EXTERNAL) {
      row("URL:") {
        urlField = textField().bindText(config::url).align(AlignX.FILL)
          .comment("For example an anvil you started in a terminal: http://127.0.0.1:8545").component
      }
      return@panel
    }
    row("Fork:") {
      cell(forkCombo).align(AlignX.FILL).resizableColumn()
      button("Edit Networks…") {
        val current = selectedFork?.url ?: ""
        if (NetworksDialog(project).showAndGet()) reloadNetworks(current)
      }
    }.rowComment("Copies a network's state into your local chain, so real contracts like Chainlink feeds work.")
    row("Fork block:") {
      forkBlockField = textField().bindText(config::forkBlock).comment("Empty for the latest block").component
      forkBlockField.isEnabled = selectedFork != null
    }
    row {
      checkBox("Keep state between runs").bindSelected(config::persistent)
        .comment("Keeps the chain's state, deployed contracts and history. Otherwise they are cleared when the chain stops.")
    }
    collapsibleGroup("Advanced") {
      row("Chain id:") { textField().bindText(config::chainId).comment("Empty for anvil's default: 31337, or the forked network's id") }
      row("Port:") { intTextField(1..65535).bindIntText(config::port).comment("The next free port is used if this one is busy") }
      row("Accounts:") {
        intTextField(1..1000).bindIntText(config::accounts).columns(4)
        label("with")
        intTextField(0..Int.MAX_VALUE).bindIntText(config::balanceEth).columns(8)
        label("ETH each")
      }
    }
  }

  override fun doOKAction() {
    super.doOKAction()
    config.forkUrl = selectedFork?.url ?: ""
    if (config.forkUrl.isEmpty()) config.forkBlock = ""
  }

  override fun doValidate(): ValidationInfo? {
    if (config.kind == ChainKind.EXTERNAL && ::urlField.isInitialized && !urlField.text.trim().matches(URL_REGEX)) {
      return ValidationInfo("Enter an http:// or ws:// URL", urlField)
    }
    if (::forkBlockField.isInitialized && forkBlockField.isEnabled && forkBlockField.text.isNotBlank() && forkBlockField.text.trim().toLongOrNull() == null) {
      return ValidationInfo("Enter a block number", forkBlockField)
    }
    return null
  }
}

/** Edit the saved list of networks (name + RPC URL) that local chains can fork. */
class NetworksDialog(project: Project) : DialogWrapper(project) {
  private val model = ListTableModel<Network>(
    column("Name") { it::name },
    column("RPC URL") { it::url },
  ).apply { items = Networks.getInstance().list }
  private val table = TableView(model)

  init {
    title = "Networks"
    init()
  }

  override fun createCenterPanel(): JComponent {
    table.setShowGrid(false)
    table.columnModel.getColumn(0).preferredWidth = JBUI.scale(160)
    table.columnModel.getColumn(1).preferredWidth = JBUI.scale(380)
    return ToolbarDecorator.createDecorator(table)
      .setAddAction {
        model.addRow(Network("New network", "https://"))
        val row = model.rowCount - 1
        table.selection = listOf(model.getItem(row))
        table.editCellAt(row, 0)
      }
      .createPanel().apply { preferredSize = JBUI.size(600, 320) }
  }

  override fun doValidate(): ValidationInfo? {
    val bad = model.items.firstOrNull { !it.url.trim().matches(URL_REGEX) } ?: return null
    return ValidationInfo("${bad.name}: the RPC URL must start with http(s):// or ws(s)://", table)
  }

  override fun doOKAction() {
    if (table.isEditing) table.cellEditor.stopCellEditing()
    Networks.getInstance().list = model.items
    super.doOKAction()
  }

  private fun column(name: String, property: (Network) -> kotlin.reflect.KMutableProperty0<String>) = object : ColumnInfo<Network, String>(name) {
    override fun valueOf(item: Network) = property(item).get()
    override fun isCellEditable(item: Network) = true
    override fun setValue(item: Network, value: String) = property(item).set(value)
  }
}

/** Attach a contract that is already on the chain, using the ABI of one of the project's contracts. */
class AttachDialog(project: Project, private val artifacts: List<Artifact>) : DialogWrapper(project) {
  var artifact: Artifact? = artifacts.firstOrNull()
  var address: String = ""
  private lateinit var addressField: JTextField

  init {
    title = "Attach Contract at Address"
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row("Contract:") {
      comboBox(artifacts, SimpleListCellRenderer.create("") { "${it.name}  (${it.sourcePath})" })
        .applyToComponent { selectedItem = artifact; addActionListener { artifact = selectedItem as Artifact? } }
        .align(AlignX.FILL)
        .comment("Interfaces work too, for example AggregatorV3Interface for a Chainlink feed")
    }
    row("Address:") {
      addressField = textField().bindText(::address).align(AlignX.FILL).focused().component
    }
  }

  override fun doValidate(): ValidationInfo? {
    val a = addressField.text.trim().removePrefix("0x")
    if (a.length != 40 || a.any { Character.digit(it, 16) < 0 }) return ValidationInfo("Not an address", addressField)
    if (artifact == null) return ValidationInfo("Pick a contract")
    return null
  }

  val checksummedAddress get() = AbiCodec.checksum(address.trim())
}
