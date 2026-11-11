package me.serce.solidity.chain.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainExecutor
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.abi.AbiInputException
import me.serce.solidity.chain.abi.AbiParam
import me.serce.solidity.chain.abi.AbiType
import me.serce.solidity.chain.abi.AbiValueFormatter
import me.serce.solidity.chain.abi.AbiValueParser
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.math.BigInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

/**
 * Argument form for a constructor or function: one field per parameter (with live unit conversion),
 * sender account, ETH value for payable functions, a run button and the result.
 */
class FunctionForm(
  private val project: Project,
  private val chain: ChainConfig,
  private val title: String,
  private val params: List<AbiParam>,
  private val payable: Boolean,
  private val actionName: String,
  /** Runs off the EDT. */
  private val action: (args: List<Any>, from: String, value: BigInteger) -> ChainExecutor.Outcome,
) {
  private val service = ChainService.getInstance(project)
  private val inputs = ArrayList<Pair<AbiParam, () -> String>>()
  private val fromCombo = ComboBox<String>()
  private val valueField = JBTextField("0")
  private val runButton = JButton(actionName)
  private val result = ResultView(project)
  val component: JComponent
  /** Called after the result area changed size, so a popup can resize to fit. */
  var onResize: () -> Unit = {}
  private val doneListeners = ArrayList<(ChainExecutor.Outcome) -> Unit>()

  /** [listener] runs on the EDT each time the action finishes. */
  fun whenDone(listener: (ChainExecutor.Outcome) -> Unit) {
    doneListeners += listener
  }

  init {
    val rt = service.runtime(chain.id)
    rt.accounts.forEach { fromCombo.addItem(it) }
    fromCombo.selectedItem = service.selectedAccount(chain.id)
    fromCombo.renderer = SimpleListCellRenderer.create("") { a ->
      val balance = rt.balances[a]?.let { "   ${AbiValueFormatter.ether(it, decimals = 4)}" } ?: ""
      "${ChainExecutor.short(a)}$balance"
    }
    fromCombo.setMinimumAndPreferredWidth(JBUI.scale(300))
    fromCombo.addActionListener { (fromCombo.selectedItem as? String)?.let { service.selectAccount(chain.id, it) } }

    val form = FormBuilder.createFormBuilder()
    form.addComponent(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() })
    form.addLabeledComponent("From:", fromCombo)
    if (payable) form.addLabeledComponent("Value:", withHint(valueField, AbiType.UInt(256), unitsHint = true))
    params.forEachIndexed { i, p ->
      val label = "${p.name.ifEmpty { "arg$i" }} ${p.displayType}"
      val editor = editorFor(p.type)
      inputs += p to editor.second
      form.addLabeledComponent(label, editor.first)
    }
    runButton.addActionListener { submit() }
    form.addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(runButton) })
    form.addComponent(result.component)
    component = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8)
      add(form.panel, BorderLayout.NORTH)
    }
  }

  val preferredFocus: JComponent? get() = component.components.firstOrNull() as? JComponent

  private fun editorFor(type: AbiType): Pair<JComponent, () -> String> = when (type) {
    AbiType.Address -> {
      val combo = ComboBox<String>().apply { isEditable = true }
      val rt = service.runtime(chain.id)
      rt.accounts.forEach { combo.addItem(it) }
      service.deployments(chain.id).forEach { combo.addItem(it.address) }
      combo.selectedItem = ""
      combo.renderer = SimpleListCellRenderer.create("") { a ->
        val d = service.deployments(chain.id).firstOrNull { it.address == a }
        if (d != null) "${d.name}  ${ChainExecutor.short(a)}" else "account ${ChainExecutor.short(a)}"
      }
      combo to { (combo.editor.item as? String).orEmpty() }
    }
    AbiType.Bool -> {
      val combo = ComboBox(arrayOf("false", "true"))
      combo to { combo.selectedItem as String }
    }
    else -> {
      val field = JBTextField()
      field.emptyText.text = placeholder(type)
      field.addActionListener { submit() }
      withHint(field, type, unitsHint = type is AbiType.UInt || type is AbiType.Int) to { field.text }
    }
  }

  private fun placeholder(type: AbiType) = when (type) {
    is AbiType.UInt, is AbiType.Int -> "1000, 1e18, 0.5 eth, 20 gwei"
    is AbiType.FixedBytes, AbiType.Bytes -> "0x…"
    is AbiType.Array -> "[a, b, …]"
    is AbiType.Tuple -> "[field1, field2, …]"
    else -> ""
  }

  /** Puts a grey line under [field] showing the converted wei value or the parse error. */
  private fun withHint(field: JTextField, type: AbiType, unitsHint: Boolean): JComponent {
    val hint = JBLabel(" ").apply {
      font = JBUI.Fonts.smallFont()
      foreground = UIUtil.getContextHelpForeground()
    }
    if (unitsHint) {
      field.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          val text = field.text.trim()
          if (text.isEmpty() || text.all { it.isDigit() }) {
            hint.text = " "
            return
          }
          try {
            val n = AbiValueParser.parse(type, text) as BigInteger
            hint.foreground = UIUtil.getContextHelpForeground()
            hint.text = "= $n wei"
          } catch (e: AbiInputException) {
            hint.foreground = JBColor.RED
            hint.text = e.message
          }
        }
      })
    }
    return JPanel(BorderLayout()).apply {
      add(field, BorderLayout.CENTER)
      add(hint, BorderLayout.SOUTH)
    }
  }

  /** Every change to the result area goes through here, so a popup always fits it. */
  private fun showResult(show: ResultView.() -> Unit) {
    result.show()
    onResize()
  }

  fun submit() {
    if (!runButton.isEnabled) return
    val from = fromCombo.selectedItem as? String
      ?: return showResult { showError("No account: is ${chain.name} running?") }
    val args = try {
      inputs.map { (p, text) ->
        try {
          AbiValueParser.parse(p.type, text())
        } catch (e: RuntimeException) { // AbiInputException and number/hex format errors
          throw AbiInputException("${p.name.ifEmpty { p.type.canonical }}: ${e.message}")
        }
      }
    } catch (e: AbiInputException) {
      return showResult { showError(e.message.orEmpty()) }
    }
    val value = if (payable) {
      AbiValueParser.parseAmount(valueField.text) ?: return showResult { showError("Value: not an amount, try 0.1 eth") }
    } else BigInteger.ZERO

    runButton.isEnabled = false
    showResult { showRunning() }
    object : Task.Backgroundable(project, "$actionName ${title.substringBefore('(')}", false) {
      private var outcome: ChainExecutor.Outcome? = null
      private var failure: Throwable? = null
      override fun run(indicator: ProgressIndicator) {
        try {
          outcome = action(args, from, value)
        } catch (t: Throwable) {
          failure = t
        }
      }

      override fun onFinished() {
        runButton.isEnabled = true
        outcome?.let {
          showResult { show(it.record) }
          doneListeners.forEach { l -> l(it) }
        }
        failure?.let { f -> showResult { showError(f.message ?: f.toString()) } }
      }
    }.queue()
  }
}
