package me.serce.solidity.chain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import me.serce.solidity.chain.ChainExecutor
import me.serce.solidity.chain.ChainListener
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.TxKind
import me.serce.solidity.chain.TxRecord
import me.serce.solidity.chain.TxStatus
import me.serce.solidity.chain.abi.AbiValueFormatter
import me.serce.solidity.chain.foundry.FoundryProject
import java.awt.BorderLayout
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellRenderer

/** The "Transactions" tab: every deploy, call and transaction, newest first, with details on the right. */
class HistoryPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
  private val service = ChainService.getInstance(project)
  private val model = ListTableModel<TxRecord>(
    column("", 24) { r ->
      icon = when (r.status) {
        TxStatus.SUCCESS -> if (r.kind == TxKind.CALL) AllIcons.Nodes.Method else AllIcons.RunConfigurations.TestPassed
        TxStatus.REVERTED -> AllIcons.RunConfigurations.TestFailed
        TxStatus.FAILED -> AllIcons.General.Error
      }
    },
    column("Time", 70) { r -> append(TIME.format(Date(r.timestamp)), SimpleTextAttributes.GRAYED_ATTRIBUTES) },
    column("Chain", 110) { r -> append(service.chain(r.chainId)?.name ?: "?") },
    column("Action", 260) { r ->
      append(r.title, if (r.status == TxStatus.SUCCESS) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
      val summary = if (r.error.isNotEmpty()) r.error else r.results.firstOrNull().orEmpty()
      if (summary.isNotEmpty()) append("  $summary", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    },
    column("From", 100) { r -> append(ChainExecutor.short(r.from)) },
    column("Value", 90) { r ->
      val v = r.valueWei.toBigIntegerOrNull() ?: BigInteger.ZERO
      if (v.signum() > 0) append(AbiValueFormatter.ether(v))
    },
    column("Gas", 70) { r -> append(r.gasUsed, SimpleTextAttributes.GRAYED_ATTRIBUTES) },
  )
  private val table = TableView(model)
  private val details = ResultView { link -> if (link == "source") selected()?.let(::navigate) }

  init {
    table.setShowGrid(false)
    table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    table.emptyText.text = "No transactions yet"
    table.emptyText.appendLine("Deploy a contract or call a function from the Chains tab")
    table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
    model.columnInfos.forEachIndexed { i, c -> table.columnModel.getColumn(i).preferredWidth = JBUI.scale((c as Col).width) }
    table.columnModel.getColumn(0).maxWidth = JBUI.scale(28)

    details.component.border = JBUI.Borders.empty(8)
    val splitter = OnePixelSplitter(false, "solidity.history.splitter", 0.6f)
    splitter.firstComponent = ScrollPaneFactory.createScrollPane(table, true)
    splitter.secondComponent = ScrollPaneFactory.createScrollPane(JPanel(BorderLayout()).apply { add(details.component, BorderLayout.NORTH) }, true)
    setContent(splitter)

    val clear = object : DumbAwareAction("Clear History", null, AllIcons.Actions.GC) {
      override fun actionPerformed(e: AnActionEvent) = service.clearHistory()
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = model.rowCount > 0
      }
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
    val toolbar = ActionManager.getInstance().createActionToolbar("SolidityChainHistory", DefaultActionGroup(clear), true)
    toolbar.targetComponent = this
    setToolbar(toolbar.component)

    project.messageBus.connect(this).subscribe(ChainListener.TOPIC, ChainListener { reload() })
    reload()
  }

  private fun selected(): TxRecord? = table.selectedRow.takeIf { it >= 0 }?.let { model.getItem(table.convertRowIndexToModel(it)) }

  private fun reload() {
    val selectedId = selected()?.id
    val history = service.history()
    model.items = history
    // select the newest entry when something new arrives, otherwise keep the selection
    val newestChanged = history.firstOrNull()?.id != previousNewest
    val index = if (newestChanged) 0 else history.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    previousNewest = history.firstOrNull()?.id
    if (history.isNotEmpty()) table.selectionModel.setSelectionInterval(index, index) else details.clear()
  }

  private var previousNewest: String? = null

  private fun showSelected() {
    selected()?.let { details.show(it, detailed = true) }
  }

  private fun navigate(r: TxRecord) {
    val fn = if (r.kind == TxKind.DEPLOY) null else r.function.substringBefore('(')
    ChainUi.navigate(project, FoundryProject.find(project)?.root, r.sourcePath, r.contractName, fn)
  }

  override fun dispose() {}

  private class Col(name: String, val width: Int, private val render: ColoredTableCellRenderer.(TxRecord) -> Unit) : ColumnInfo<TxRecord, TxRecord>(name) {
    override fun valueOf(item: TxRecord) = item
    override fun getRenderer(item: TxRecord?): TableCellRenderer = object : ColoredTableCellRenderer() {
      override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        (value as? TxRecord)?.let { render(it) }
      }
    }
  }

  companion object {
    private val TIME = SimpleDateFormat("HH:mm:ss")
    private fun column(name: String, width: Int, render: ColoredTableCellRenderer.(TxRecord) -> Unit): ColumnInfo<TxRecord, TxRecord> = Col(name, width, render)
  }
}
