package me.serce.solidity.chain.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainExecutor
import me.serce.solidity.chain.ChainKind
import me.serce.solidity.chain.ChainListener
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.ChainStatus
import me.serce.solidity.chain.Deployment
import me.serce.solidity.chain.KnownChains
import me.serce.solidity.chain.Secrets
import me.serce.solidity.chain.abi.AbiFetcher
import me.serce.solidity.chain.abi.AbiText
import me.serce.solidity.chain.wallet.CastWallet
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Files
import com.intellij.ide.BrowserUtil
import me.serce.solidity.chain.abi.AbiEntry
import me.serce.solidity.chain.abi.AbiValueFormatter
import me.serce.solidity.chain.abi.Mutability
import me.serce.solidity.chain.foundry.FoundryProject
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** Tree items. [key] identifies a node across rebuilds so selection and expansion survive refreshes. */
sealed class ChainItem(val key: String, val config: ChainConfig) {
  class Chain(config: ChainConfig) : ChainItem("chain:${config.id}", config)
  class Accounts(config: ChainConfig) : ChainItem("accounts:${config.id}", config)
  class Account(config: ChainConfig, val address: String) : ChainItem("account:${config.id}:$address", config)
  class Contract(config: ChainConfig, val deployment: Deployment) : ChainItem("contract:${deployment.id}", config)
  class Function(config: ChainConfig, val deployment: Deployment, val fn: AbiEntry.Function) :
    ChainItem("fn:${deployment.id}:${fn.signature}", config)
}

/** The "Chains" tab: chains, their accounts and deployed contracts on the left, details or a call form on the right. */
class ChainsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
  private val service = ChainService.getInstance(project)
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model)
  private val details = JBPanel<JBPanel<*>>(BorderLayout())
  /** A form keeps the sender list and config it was built with, so it is only reused within one run of an unchanged chain. */
  private class CachedForm(val config: ChainConfig, val rpc: Any?, val form: FunctionForm)
  private val forms = HashMap<String, CachedForm>()
  private val collapsed = HashSet<String>()
  private var rebuilding = false

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    tree.cellRenderer = Renderer()
    tree.emptyText.text = "No chains yet"
    tree.emptyText.appendLine("Add a local chain", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addChain(ChainKind.ANVIL) }
    tree.emptyText.appendLine("Connect to a network (Sepolia, Mainnet, …)", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addChain(ChainKind.NETWORK) }
    tree.emptyText.appendLine("Connect to a node by URL", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addChain(ChainKind.EXTERNAL) }
    tree.addTreeSelectionListener { if (!rebuilding) showDetails() }
    tree.addTreeExpansionListener(object : TreeExpansionListener {
      private fun key(e: TreeExpansionEvent) = e.path.lastPathComponent.chainItem?.key
      override fun treeExpanded(e: TreeExpansionEvent) { if (!rebuilding) key(e)?.let { collapsed -= it } }
      override fun treeCollapsed(e: TreeExpansionEvent) { if (!rebuilding) key(e)?.let { collapsed += it } }
    })
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        when (val item = selected()) {
          is ChainItem.Contract -> navigate(item.deployment, null)
          is ChainItem.Function -> navigate(item.deployment, item.fn.name)
          else -> return false
        }
        return true
      }
    }.installOn(tree)

    val splitter = OnePixelSplitter(false, "solidity.chains.splitter", 0.4f)
    splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
    splitter.secondComponent = ScrollPaneFactory.createScrollPane(details, true)
    setContent(splitter)

    val toolbarGroup = DefaultActionGroup(
      AddChainGroup(), StartAction(), StopAction(), Separator(),
      DeployAction(), AttachAction(), BuildAction(), Separator(),
      RefreshAction(), EditAction(), RemoveAction(), Separator(),
      CompileWhileEditingAction(),
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("SolidityChains", toolbarGroup, true)
    toolbar.targetComponent = this
    setToolbar(toolbar.component)
    PopupHandler.installPopupMenu(tree, DefaultActionGroup(
      StartAction(), StopAction(), Separator(), DeployAction(), AttachAction(), Separator(),
      CopyAddressAction(), OpenInExplorerAction(), GenerateInterfaceAction(), Separator(), RefreshAction(), EditAction(), RemoveAction(),
    ), "SolidityChainsPopup")

    project.messageBus.connect(this).subscribe(ChainListener.TOPIC, ChainListener { rebuild() })
    rebuild()
  }

  private fun selected(): ChainItem? = tree.lastSelectedPathComponent.chainItem

  private fun selectedChain(): ChainConfig? = selected()?.config ?: service.chains.singleOrNull()

  private fun rebuild() {
    val expanded = HashSet<String>()
    TreeUtil.collectExpandedPaths(tree).forEach { p -> p.lastPathComponent.chainItem?.let { expanded += it.key } }
    val selectedKey = selected()?.key
    rebuilding = true
    root.removeAllChildren()
    for (chain in service.chains) {
      val rt = service.runtime(chain.id)
      val chainNode = DefaultMutableTreeNode(ChainItem.Chain(chain))
      if (service.isRunning(chain)) {
        val accounts = DefaultMutableTreeNode(ChainItem.Accounts(chain))
        rt.accounts.forEach { accounts.add(DefaultMutableTreeNode(ChainItem.Account(chain, it))) }
        chainNode.add(accounts)
      }
      for (d in service.deployments(chain.id)) {
        val contract = DefaultMutableTreeNode(ChainItem.Contract(chain, d))
        d.sortedFunctions().forEach { contract.add(DefaultMutableTreeNode(ChainItem.Function(chain, d, it))) }
        chainNode.add(contract)
      }
      root.add(chainNode)
    }
    model.reload()

    var toSelect: TreePath? = null
    TreeUtil.treeNodeTraverser(root).forEach { n ->
      val node = n as DefaultMutableTreeNode
      val item = node.chainItem ?: return@forEach
      val path = TreePath(node.path)
      // chains and contracts are expanded unless the user collapsed them; accounts only when the user opened them
      val expand = if (item is ChainItem.Chain || item is ChainItem.Contract) item.key !in collapsed else item.key in expanded
      val parentKey = node.parent.chainItem?.key
      if (expand && parentKey !in collapsed) tree.expandPath(path)
      if (item.key == selectedKey) toSelect = path
    }
    // keep forms only for functions that still exist
    val liveKeys = TreeUtil.treeNodeTraverser(root).mapNotNull { it.chainItem?.key }.toSet()
    forms.keys.retainAll(liveKeys)
    rebuilding = false
    if (toSelect != null) tree.selectionPath = toSelect else showDetails()
  }

  private fun showDetails() {
    details.removeAll()
    val component: JComponent? = when (val item = selected()) {
      is ChainItem.Chain -> chainDetails(item.config)
      is ChainItem.Accounts -> null
      is ChainItem.Account -> accountDetails(item)
      is ChainItem.Contract -> contractDetails(item)
      is ChainItem.Function -> {
        if (!service.isRunning(item.config)) message("Start ${item.config.name} to call ${item.fn.name}")
        else {
          val rpc = service.runtime(item.config.id).rpc
          val cached = forms[item.key]?.takeIf { it.config === item.config && it.rpc === rpc }
          (cached ?: CachedForm(item.config, rpc, ChainUi.callForm(project, item.config, item.deployment, item.fn)).also { forms[item.key] = it })
            .form.component
        }
      }
      null -> message(if (service.chains.isEmpty()) "Add a chain with + to deploy contracts" else "Select a chain, contract or function")
    }
    component?.let { details.add(it, BorderLayout.CENTER) }
    details.revalidate()
    details.repaint()
  }

  private fun message(text: String): JComponent = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(12)
    add(JBLabel(text).apply { foreground = StatusText.DEFAULT_ATTRIBUTES.fgColor }, BorderLayout.NORTH)
  }

  private fun chainDetails(chain: ChainConfig): JComponent {
    val rt = service.runtime(chain.id)
    val info = buildString {
      append("<b>${ResultView.esc(chain.name)}</b> <span style='color:${ResultView.grey()}'>${ResultView.esc(chain.description)}</span><br>")
      append("status: ${rt.status.name.lowercase()}")
      if (service.isRunning(chain)) {
        append(" · <code>${ResultView.esc(rt.rpc?.let { if (chain.isNetwork) it.displayUrl else it.url }.orEmpty())}</code> · chain id ${rt.chainId} · block ${rt.blockNumber}")
      }
      if (chain.isNetwork) {
        val known = KnownChains.info(rt.chainId ?: chain.expectedChainId.toLongOrNull())
        append("<br>")
        append(if (KnownChains.isMainnet(rt.chainId ?: chain.expectedChainId.toLongOrNull()))
          "<span style='color:${ResultView.red()}'>real network: transactions spend real funds</span>" else "test network")
        if (known != null) append(" · ${ResultView.esc(known.name)}")
        if (chain.wallets.isEmpty()) append("<br>No wallets: calls only. Edit the chain to add a wallet for transactions.")
      }
      if (chain.kind == ChainKind.ANVIL) append("<br>${if (chain.persistent) "state is kept between runs" else "temporary: cleared when stopped"}")
      rt.error?.let { append("<br><span style='color:${ResultView.red()}'>${ResultView.esc(it)}</span>") }
    }
    val header = JBLabel(ResultView.html(info)).apply { border = JBUI.Borders.empty(8) }
    return JPanel(BorderLayout()).apply {
      add(header, BorderLayout.NORTH)
      rt.console?.let { add(it.component, BorderLayout.CENTER) }
    }
  }

  private fun accountDetails(item: ChainItem.Account): JComponent {
    val balance = service.runtime(item.config.id).balances[item.address]
    val selected = service.selectedAccount(item.config.id) == item.address
    return message(ResultView.html(
      "<code>${item.address}</code><br>balance: ${balance?.let { AbiValueFormatter.ether(it) } ?: "?"}" +
        if (selected) "<br>used as the sender for calls" else ""
    ))
  }

  private fun contractDetails(item: ChainItem.Contract): JComponent {
    val d = item.deployment
    val missing = d.id in service.runtime(item.config.id).missing
    val origin = d.sourcePath.ifEmpty { d.abiSource.takeIf { it.isNotEmpty() }?.let { "ABI: $it" }.orEmpty() }
    val html = "<b>${ResultView.esc(d.name)}</b>" + (if (origin.isNotEmpty()) " <span style='color:${ResultView.grey()}'>${ResultView.esc(origin)}</span>" else "") +
      "<br><code>${d.address}</code>" +
      (if (missing) "<br><span style='color:${ResultView.red()}'>No code at this address any more (the chain was reset)</span>" else "") +
      "<br><br>Pick a function on the left to call it" + (if (d.sourcePath.isNotEmpty()) ", or double-click to open its source." else ".")
    return message(ResultView.html(html))
  }

  private fun navigate(d: Deployment, fn: String?) {
    ChainUi.navigate(project, d.sourcePath, d.name, fn)
  }

  private fun addChain(kind: ChainKind) {
    val config = ChainConfig().apply {
      this.kind = kind
      name = when (kind) {
        ChainKind.EXTERNAL -> "Running node"
        ChainKind.NETWORK -> "Sepolia"
        ChainKind.ANVIL -> "Local chain"
      }
      if (kind == ChainKind.NETWORK) network = "Sepolia"
      port = 8545 + service.chains.size
    }
    if (ChainDialog(project, config, isNew = true).showAndGet()) {
      service.addChain(config)
      service.start(config)
      selectKey("chain:${config.id}")
    }
  }

  private fun selectKey(key: String) {
    TreeUtil.treeNodeTraverser(root).forEach { n ->
      val node = n as DefaultMutableTreeNode
      if (node.chainItem?.key == key) tree.selectionPath = TreePath(node.path)
    }
  }

  override fun dispose() {}

  // ---- rendering ----

  private inner class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val item = value.chainItem) {
        is ChainItem.Chain -> {
          val rt = service.runtime(item.config.id)
          icon = when (rt.status) {
            ChainStatus.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            ChainStatus.STARTING -> AllIcons.Process.Step_1
            ChainStatus.FAILED -> AllIcons.General.Error
            ChainStatus.STOPPED -> AllIcons.Nodes.EmptyNode
          }
          append(item.config.name)
          append("  ${item.config.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          if (rt.status == ChainStatus.STARTING) append("  starting…", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        is ChainItem.Accounts -> {
          icon = AllIcons.Nodes.Folder
          append("Accounts")
          append("  ${service.runtime(item.config.id).accounts.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is ChainItem.Account -> {
          icon = AllIcons.General.User
          val isSender = service.selectedAccount(item.config.id) == item.address
          append(ChainExecutor.short(item.address), if (isSender) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          service.runtime(item.config.id).balances[item.address]?.let { append("  ${AbiValueFormatter.ether(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
        is ChainItem.Contract -> {
          val missing = item.deployment.id in service.runtime(item.config.id).missing
          icon = AllIcons.Nodes.Class
          append(item.deployment.name, if (missing) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          append("  ${ChainExecutor.short(item.deployment.address)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is ChainItem.Function -> {
          val fn = item.fn
          icon = if (fn.mutability.isReadOnly) AllIcons.Nodes.Method else AllIcons.Nodes.AbstractMethod
          append(fn.name, if (fn.mutability.isReadOnly) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBUI.CurrentTheme.Link.Foreground.ENABLED))
          append("(${fn.inputs.joinToString(", ") { it.displayType }})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          if (fn.outputs.isNotEmpty()) append(" → ${fn.outputs.joinToString(", ") { it.displayType }}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          if (fn.mutability != Mutability.NONPAYABLE) append("  ${fn.mutability.name.lowercase()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        null -> {}
      }
    }
  }

  // ---- actions ----

  private abstract inner class ChainAction(text: String, icon: javax.swing.Icon?) : DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
  }

  private inner class AddChainGroup : DefaultActionGroup("Add Chain", true) {
    init {
      templatePresentation.icon = AllIcons.General.Add
      add(object : ChainAction("Local Chain…", null) {
        override fun actionPerformed(e: AnActionEvent) = addChain(ChainKind.ANVIL)
      })
      add(object : ChainAction("Connect to Network…", null) {
        override fun actionPerformed(e: AnActionEvent) = addChain(ChainKind.NETWORK)
      })
      add(object : ChainAction("Connect to Node by URL…", null) {
        override fun actionPerformed(e: AnActionEvent) = addChain(ChainKind.EXTERNAL)
      })
      addSeparator()
      add(object : ChainAction("Edit Networks…", null) {
        override fun actionPerformed(e: AnActionEvent) {
          NetworksDialog(project).show()
        }
      })
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
  }

  private inner class StartAction : ChainAction("Start", AllIcons.Actions.Execute) {
    override fun update(e: AnActionEvent) {
      val chain = selectedChain()
      val status = chain?.let { service.runtime(it.id).status }
      e.presentation.isEnabled = status == ChainStatus.STOPPED || status == ChainStatus.FAILED
      e.presentation.text = if (chain?.kind == ChainKind.ANVIL) "Start" else "Connect"
    }

    override fun actionPerformed(e: AnActionEvent) {
      selectedChain()?.let { service.start(it) }
    }
  }

  private inner class StopAction : ChainAction("Stop", AllIcons.Actions.Suspend) {
    override fun update(e: AnActionEvent) {
      val status = selectedChain()?.let { service.runtime(it.id).status }
      e.presentation.isEnabled = status == ChainStatus.RUNNING || status == ChainStatus.STARTING
    }

    override fun actionPerformed(e: AnActionEvent) {
      val chain = selectedChain() ?: return
      if (chain.kind == ChainKind.ANVIL && !chain.persistent && service.deployments(chain.id).isNotEmpty()) {
        val ok = Messages.showOkCancelDialog(project, "${chain.name} is temporary: its deployed contracts and history will be cleared.",
          "Stop ${chain.name}", "Stop", Messages.getCancelButton(), null)
        if (ok != Messages.OK) return
      }
      service.stop(chain)
    }
  }

  private inner class DeployAction : ChainAction("Deploy Contract…", AllIcons.Nodes.Deploy) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = selectedChain()?.let(service::isRunning) == true
    }

    override fun actionPerformed(e: AnActionEvent) {
      val chain = selectedChain() ?: return
      val foundry = FoundryProject.find(project) ?: return noFoundry()
      ChainUi.buildThen(project, foundry) { artifacts ->
        val deployable = artifacts.filter { it.isDeployable && foundry.isSource(it.sourcePath) }
        if (deployable.isEmpty()) {
          Messages.showInfoMessage(project, "No deployable contracts in ${foundry.config().src}/", "Deploy Contract")
          return@buildThen
        }
        ChainUi.choose("Deploy to ${chain.name}", deployable, { "${it.name}  (${it.sourcePath})" }, null, project) { artifact ->
          val form = ChainUi.deployForm(project, chain, artifact)
          form.whenDone { outcome -> outcome.deployment?.let { selectKey("contract:${it.id}") } }
          ChainUi.showFormPopup(project, form, null, closeOnSuccess = true)
        }
      }
    }
  }

  private inner class AttachAction : ChainAction("Attach Contract at Address…", AllIcons.General.Locate) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = selectedChain() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val chain = selectedChain() ?: return
      // without a Foundry project (or a project that doesn't build) contracts are attached by ABI
      val foundry = FoundryProject.find(project) ?: return attach(chain, emptyList())
      ChainUi.buildThen(project, foundry, onFailure = { attach(chain, emptyList()) }) { artifacts ->
        attach(chain, artifacts.filter { runCatching { it.abi.functions.isNotEmpty() }.getOrDefault(false) })
      }
    }

    private fun attach(chain: ChainConfig, artifacts: List<me.serce.solidity.chain.foundry.Artifact>) {
      val rt = service.runtime(chain.id)
      val chainId = rt.chainId?.takeIf { service.isRunning(chain) }
      val fetch = chainId?.let { id -> { address: String -> AbiFetcher(::etherscanKey).fetch(rt.rpc, id, address) } }
      val dialog = AttachDialog(project, artifacts, fetch)
      if (!dialog.showAndGet()) return
      val d = dialog.deployment(chain.id)
      service.addDeployment(d)
      service.refresh(chain)
      selectKey("contract:${d.id}")
    }
  }

  private inner class BuildAction : ChainAction("Build with Forge", AllIcons.Actions.Compile) {
    override fun actionPerformed(e: AnActionEvent) {
      val foundry = FoundryProject.find(project) ?: return noFoundry()
      ChainUi.buildThen(project, foundry) {}
    }
  }

  private inner class RefreshAction : ChainAction("Refresh", AllIcons.Actions.Refresh) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = selectedChain()?.let(service::isRunning) == true
    }

    override fun actionPerformed(e: AnActionEvent) {
      selectedChain()?.let { service.refresh(it) }
    }
  }

  private inner class EditAction : ChainAction("Edit Chain…", AllIcons.Actions.Edit) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = selected() is ChainItem.Chain
    }

    override fun actionPerformed(e: AnActionEvent) {
      val chain = (selected() as? ChainItem.Chain)?.config ?: return
      val copy = chain.copy()
      if (ChainDialog(project, copy, isNew = false).showAndGet()) {
        service.updateChain(copy)
        if (service.isRunning(chain)) {
          // wallets apply at once; another network needs a new connection
          if (copy.isNetwork && copy.network == chain.network) service.refresh(copy)
          else Messages.showInfoMessage(project, "Restart ${copy.name} for the changes to take effect.", "Chain Updated")
        }
      }
    }
  }

  private inner class RemoveAction : ChainAction("Remove", AllIcons.General.Remove) {
    override fun update(e: AnActionEvent) {
      val item = selected()
      e.presentation.isEnabled = item is ChainItem.Chain || item is ChainItem.Contract
      e.presentation.text = if (item is ChainItem.Contract) "Remove Contract from List" else "Remove Chain"
    }

    override fun actionPerformed(e: AnActionEvent) {
      when (val item = selected()) {
        is ChainItem.Chain -> {
          val ok = Messages.showOkCancelDialog(project, "Remove ${item.config.name} with its deployed contracts and history?",
            "Remove Chain", "Remove", Messages.getCancelButton(), null)
          if (ok == Messages.OK) service.removeChain(item.config)
        }
        is ChainItem.Contract -> service.removeDeployment(item.deployment)
        else -> {}
      }
    }
  }

  private inner class CopyAddressAction : ChainAction("Copy Address", AllIcons.Actions.Copy) {
    private fun address() = when (val item = selected()) {
      is ChainItem.Account -> item.address
      is ChainItem.Contract -> item.deployment.address
      is ChainItem.Function -> item.deployment.address
      else -> null
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = address() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      address()?.let { CopyPasteManagerEx.getInstance().setContents(StringSelection(it)) }
    }
  }

  private inner class OpenInExplorerAction : ChainAction("Open in Block Explorer", AllIcons.General.Web) {
    private fun url(): String? {
      val item = selected() ?: return null
      val chainId = service.runtime(item.config.id).chainId ?: item.config.expectedChainId.toLongOrNull()
      if (!item.config.isNetwork) return null
      val address = when (item) {
        is ChainItem.Account -> item.address
        is ChainItem.Contract -> item.deployment.address
        is ChainItem.Function -> item.deployment.address
        else -> return null
      }
      return KnownChains.addressUrl(chainId, address)
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = url() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      url()?.let { BrowserUtil.browse(it) }
    }
  }

  /** Writes a Solidity interface for a contract attached by ABI, so code can call it with completion and checks. */
  private inner class GenerateInterfaceAction : ChainAction("Generate Solidity Interface…", AllIcons.Nodes.Interface) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = selected() is ChainItem.Contract
    }

    override fun actionPerformed(e: AnActionEvent) {
      val d = (selected() as? ChainItem.Contract)?.deployment ?: return
      val base = d.name.replace(Regex("[^A-Za-z0-9_]"), "").ifEmpty { "Contract" }
      val name = if (base.length > 1 && base[0] == 'I' && base[1].isUpperCase()) base else "I$base"
      val cast = CastWallet.castPath(project)
      val generated = try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>({
        val abiFile = Files.createTempFile("abi", ".json")
        try {
          Files.writeString(abiFile, AbiText.withStructNames(d.abiJson, name))
          val out = CapturingProcessHandler(GeneralCommandLine(cast, "interface", abiFile.toString(), "-n", name)).runProcess(60_000)
          if (out.exitCode != 0) throw IllegalStateException(CastWallet.errorMessage(out.stderr + "\n" + out.stdout))
          out.stdout
        } finally {
          Files.deleteIfExists(abiFile)
        }
        }, "Generating interface", false, project)
      } catch (ex: Exception) {
        Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Generate Solidity Interface")
        return
      }
      val root = FoundryProject.find(project)?.root?.let { r -> r.resolve("src").takeIf { Files.isDirectory(it) } ?: r }
      val dir = root?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
      val target = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save Interface", "Where to write $name", "sol"), project)
        .save(dir, "$name.sol") ?: return
      val file = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
        val vf = target.getVirtualFile(true)!!
        VfsUtil.saveText(vf, generated)
        vf
      }
      OpenFileDescriptor(project, file).navigate(true)
    }
  }

  private inner class CompileWhileEditingAction : ToggleAction("Show Compiler Errors While Editing", null, AllIcons.General.InspectionsEye) {
    override fun isSelected(e: AnActionEvent) = service.compileWhileEditing
    override fun setSelected(e: AnActionEvent, state: Boolean) {
      service.compileWhileEditing = state
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
  }

  private fun etherscanKey(): String? = Secrets.get(Secrets.ETHERSCAN_KEY) ?: System.getenv("ETHERSCAN_API_KEY")?.takeIf { it.isNotBlank() }

  private fun noFoundry() {
    Messages.showWarningDialog(project, "No foundry.toml found in this project.", "Foundry Project Not Found")
  }
}

private val Any?.chainItem: ChainItem? get() = (this as? DefaultMutableTreeNode)?.userObject as? ChainItem
