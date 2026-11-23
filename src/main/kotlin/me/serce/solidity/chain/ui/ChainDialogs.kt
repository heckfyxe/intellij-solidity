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
import me.serce.solidity.chain.Deployment
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.abi.AbiInputException
import me.serce.solidity.chain.abi.AbiText
import me.serce.solidity.chain.abi.AbiFetchException
import me.serce.solidity.chain.abi.FetchedAbi
import me.serce.solidity.chain.Secrets
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.layout.selected
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.wallet.WalletsEditor
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

private val URL_REGEX = Regex("^(https?|wss?)://.+")

private val PRETTY = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

/** A network's URL as the UI shows it: secret ones (they may hold an API key) only up to the host. */
fun displayUrl(n: Network): String = if (n.secret) JsonRpcClient.redact(n.url) + "/…" else n.url

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
  private val networkModel = CollectionComboBoxModel<Network>()
  private val networkCombo = ComboBox(networkModel)
  private lateinit var expectedChainIdField: JTextField
  private val walletsEditor = WalletsEditor(project, config.wallets)
  private var nameEditedByUser = !isNew
  private var settingName = false

  init {
    title = when {
      !isNew -> "Edit ${config.name}"
      config.kind == ChainKind.EXTERNAL -> "Connect to Node"
      config.kind == ChainKind.NETWORK -> "Connect to Network"
      else -> "Add Local Chain"
    }
    forkCombo.renderer = SimpleListCellRenderer.create("") { n ->
      if (n == null) "None: start an empty chain" else "${n.name}   ${displayUrl(n)}"
    }
    networkCombo.renderer = SimpleListCellRenderer.create("") { n -> n?.let { "${it.name}   ${displayUrl(it)}" } ?: "" }
    reloadNetworks(selectUrl = config.forkUrl)
    forkCombo.addActionListener { onForkChanged() }
    networkCombo.addActionListener { onNetworkChanged() }
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
    val selectedNetwork = (networkCombo.selectedItem as Network?)?.name ?: config.network
    networkModel.replaceAll(Networks.getInstance().list)
    networkModel.selectedItem = networkModel.items.firstOrNull { it.name == selectedNetwork } ?: networkModel.items.firstOrNull()
  }

  private fun onNetworkChanged() {
    if (!nameEditedByUser && ::nameField.isInitialized) {
      settingName = true
      nameField.text = (networkCombo.selectedItem as Network?)?.name ?: "Network"
      settingName = false
    }
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
    if (config.kind == ChainKind.NETWORK) {
      row("Network:") {
        cell(networkCombo).align(AlignX.FILL).resizableColumn()
        button("Edit Networks…") {
          if (NetworksDialog(project).showAndGet()) reloadNetworks(config.forkUrl)
        }
      }.rowComment("Add your own RPC (Alchemy, Infura, …) with Edit Networks. URLs other than the built-in public ones are kept in the system keychain.")
      group("Wallets") {
        row { cell(walletsEditor.component).align(Align.FILL) }.resizableRow()
          .rowComment("Accounts that sign transactions, through Foundry's cast. Every transaction is shown for confirmation before it is signed.")
      }.resizableRow()
      collapsibleGroup("Advanced") {
        row("Chain id:") {
          expectedChainIdField = textField().bindText(config::expectedChainId).columns(12)
            .comment("Filled when first connected. The chain refuses to connect when the network reports another id; clear it to accept a new one.")
            .component
        }
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
    if (config.kind == ChainKind.NETWORK) {
      walletsEditor.apply()
      val network = networkCombo.selectedItem as Network?
      if (network != null && network.name != config.network) {
        // another network: pin the chain id it reports on the next connect, unless the user typed one
        if (config.expectedChainId == initialExpectedChainId) config.expectedChainId = ""
        config.network = network.name
      }
      return
    }
    config.forkUrl = selectedFork?.url ?: ""
    if (config.forkUrl.isEmpty()) config.forkBlock = ""
  }

  private val initialExpectedChainId = config.expectedChainId

  override fun doValidate(): ValidationInfo? {
    if (config.kind == ChainKind.NETWORK) {
      if (networkCombo.selectedItem == null) return ValidationInfo("Pick a network", networkCombo)
      if (::expectedChainIdField.isInitialized && expectedChainIdField.text.isNotBlank() && expectedChainIdField.text.trim().toLongOrNull() == null) {
        return ValidationInfo("Enter a number", expectedChainIdField)
      }
      return null
    }
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

/**
 * Attach a contract that is already on the chain: by a compiled contract of this project, or by an ABI alone
 * (pasted, opened from a file, or written as signatures), for contracts whose source you don't have.
 */
class AttachDialog(
  private val project: Project,
  private val artifacts: List<Artifact>,
  /** Looks up a verified ABI by address; null when the chain isn't connected. Runs off the EDT. */
  private val fetch: ((address: String) -> FetchedAbi)? = null,
) : DialogWrapper(project) {
  var artifact: Artifact? = artifacts.firstOrNull()
  var address: String = ""
  private lateinit var addressField: JTextField
  private val fromAbi = JBRadioButton("ABI", artifacts.isEmpty())
  private val fromCompiled = JBRadioButton("Compiled contract", artifacts.isNotEmpty())
  private val nameField = JBTextField()
  private val abiArea = JBTextArea(12, 60)
  /** Where [abiArea]'s text came from, when not typed: a file name. */
  private var abiOrigin = "pasted"

  init {
    title = "Attach Contract at Address"
    fromCompiled.isEnabled = artifacts.isNotEmpty()
    abiArea.font = EditorUtil.getEditorFont()
    abiArea.emptyText.text = "[{\"type\":\"function\", …}]  or  function balanceOf(address) view returns (uint256)"
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    buttonsGroup {
      row("Interface:") {
        cell(fromCompiled)
        cell(fromAbi)
      }.rowComment(if (artifacts.isEmpty()) "No compiled contracts in this project: attach by ABI" else "")
    }
    row("Contract:") {
      comboBox(artifacts, SimpleListCellRenderer.create("") { "${it.name}  (${it.sourcePath})" })
        .applyToComponent { selectedItem = artifact; addActionListener { artifact = selectedItem as Artifact? } }
        .align(AlignX.FILL)
        .comment("Interfaces work too, for example AggregatorV3Interface for a Chainlink feed")
    }.visibleIf(fromCompiled.selected)
    row("Name:") {
      cell(nameField).align(AlignX.FILL).comment("Shown in the Chains tree; taken from the ABI when it says")
    }.visibleIf(fromAbi.selected)
    row {
      cell(JBScrollPane(abiArea)).align(Align.FILL)
        .comment("A JSON ABI, a Forge or Hardhat artifact, an Etherscan or Sourcify response, or signatures one per line " +
          "(a Solidity interface works too). Structs are written as tuple(type a, type b).")
    }.resizableRow().visibleIf(fromAbi.selected)
    row {
      button("Open File…") { openFile() }
      button("Fetch Verified ABI") { fetchAbi() }.enabled(fetch != null)
        .comment(if (fetch == null) "Connect the chain to fetch ABIs" else "From Sourcify, and from Etherscan with an API key")
      link("Etherscan API key…") { editEtherscanKey() }
    }.visibleIf(fromAbi.selected)
    row("Address:") {
      addressField = textField().bindText(::address).align(AlignX.FILL).focused().component
    }
  }

  private fun openFile() {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Open ABI")
    val file = FileChooser.chooseFile(descriptor, project, null) ?: return
    abiArea.text = String(file.contentsToByteArray(), file.charset)
    abiArea.caretPosition = 0
    abiOrigin = file.name
    if (nameField.text.isBlank()) runCatching { AbiText.parse(abiArea.text).name }.getOrNull().let { name ->
      nameField.text = name ?: file.nameWithoutExtension.removeSuffix(".abi")
    }
  }

  private fun fetchAbi() {
    val fetcher = fetch ?: return
    val a = addressField.text.trim()
    if (!isAddress(a)) {
      setErrorText("Enter the contract's address first", addressField)
      return
    }
    val result = try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously<FetchedAbi, AbiFetchException>(
        { fetcher(AbiCodec.checksum(a)) }, "Fetching ABI", true, project)
    } catch (e: AbiFetchException) {
      setErrorText(e.message)
      return
    }
    abiArea.text = PRETTY.writeValueAsString(PRETTY.readTree(result.json))
    abiArea.caretPosition = 0
    abiOrigin = result.source + (result.implementation?.let { ", proxy of $it" } ?: "")
    result.name?.let { nameField.text = if (result.implementation != null) "$it (proxy)" else it }
    setErrorText(null)
  }

  private fun editEtherscanKey() {
    val key = Messages.showPasswordDialog(project,
      "Etherscan API key, kept in the system keychain. One key works for every chain Etherscan supports. Leave empty to remove it.",
      "Etherscan API Key", null) ?: return
    Secrets.set(Secrets.ETHERSCAN_KEY, key.trim())
  }

  private fun isAddress(text: String): Boolean {
    val a = text.trim().removePrefix("0x")
    return a.length == 40 && a.all { Character.digit(it, 16) >= 0 }
  }

  /** The parsed ABI when attaching by ABI; null with a validation error otherwise. */
  private fun parsedAbi(): Pair<AbiText.Parsed?, String?> = try {
    AbiText.parse(abiArea.text) to null
  } catch (e: AbiInputException) {
    null to e.message
  }

  override fun doValidate(): ValidationInfo? {
    if (!isAddress(addressField.text)) return ValidationInfo("Not an address", addressField)
    if (fromCompiled.isSelected && artifact == null) return ValidationInfo("Pick a contract")
    if (fromAbi.isSelected) {
      val (_, error) = parsedAbi()
      if (error != null) return ValidationInfo(error, abiArea)
    }
    return null
  }

  val checksummedAddress get() = AbiCodec.checksum(address.trim())

  /** Call after OK: the contract to add to [chainId]. */
  fun deployment(chainId: String): Deployment = Deployment().also { d ->
    d.chainId = chainId
    d.address = checksummedAddress
    val compiled = artifact
    if (fromCompiled.isSelected && compiled != null) {
      d.name = compiled.name
      d.sourcePath = compiled.sourcePath
      d.abiJson = compiled.abiJson
    } else {
      val parsed = parsedAbi().first!!
      d.name = nameField.text.trim().ifEmpty { parsed.name ?: "Contract" }
      d.abiJson = parsed.json
      d.abiSource = abiOrigin
    }
  }
}
