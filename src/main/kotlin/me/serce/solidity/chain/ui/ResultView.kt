package me.serce.solidity.chain.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.serce.solidity.chain.TxKind
import me.serce.solidity.chain.TxRecord
import me.serce.solidity.chain.TxStatus
import me.serce.solidity.chain.abi.AbiValueFormatter
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/** Read-only HTML view of a transaction's outcome. */
class ResultView(private val onLink: (String) -> Unit = {}) {
  val component = JEditorPane().apply {
    editorKit = HTMLEditorKitBuilder.simple()
    isEditable = false
    isOpaque = false
    border = JBUI.Borders.emptyTop(8)
    addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(it.description) }
  }

  fun clear() {
    component.text = ""
  }

  fun showRunning() {
    component.text = html("<span style='color:${grey()}'>Running…</span>")
  }

  fun showError(message: String) {
    component.text = html("<span style='color:${red()}'>${esc(message)}</span>")
  }

  fun show(record: TxRecord, detailed: Boolean = false) {
    component.text = html(render(record, detailed))
    component.caretPosition = 0
  }

  companion object {
    private val TIME = SimpleDateFormat("HH:mm:ss")

    fun esc(s: String): String = StringUtil.escapeXmlEntities(s)
    fun grey() = colorHex(UIUtil.getContextHelpForeground())
    fun red() = colorHex(JBColor.RED)
    private fun green() = colorHex(JBColor(0x2E7D32, 0x5FB865))
    private fun colorHex(c: java.awt.Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    fun html(body: String) =
      "<html><body style='font-family:${UIUtil.getLabelFont().family}; font-size:${UIUtil.getLabelFont().size}pt'>$body</body></html>"

    fun render(r: TxRecord, detailed: Boolean): String = buildString {
      val (color, word) = when (r.status) {
        TxStatus.SUCCESS -> green() to if (r.kind == TxKind.CALL) "✓ call" else "✓ success"
        TxStatus.REVERTED -> red() to "✗ reverted"
        TxStatus.FAILED -> red() to "✗ failed"
      }
      append("<b style='color:$color'>$word</b>")
      if (r.gasUsed.isNotEmpty()) append(" <span style='color:${grey()}'>· ${r.gasUsed} gas · block ${r.blockNumber}</span>")
      if (detailed) {
        append("<br><b>${esc(r.title)}</b> <span style='color:${grey()}'>${TIME.format(Date(r.timestamp))}</span>")
        if (r.sourcePath.isNotEmpty()) append(" &nbsp;<a href='source'>Go to source</a>")
        row("from", r.from)
        if (r.to.isNotEmpty()) row(if (r.kind == TxKind.DEPLOY) "contract" else "to", r.to)
        val value = r.valueWei.toBigIntegerOrNull() ?: BigInteger.ZERO
        if (value.signum() > 0) row("value", "${AbiValueFormatter.ether(value)} (${r.valueWei} wei)")
        if (r.txHash.isNotEmpty()) row("tx", r.txHash)
        if (r.args.isNotEmpty()) section("Arguments", r.args)
      }
      if (r.error.isNotEmpty()) append("<br><span style='color:${red()}'>${esc(r.error)}</span>")
      if (r.results.isNotEmpty()) section(if (r.kind == TxKind.TRANSACT) "Returned (simulated)" else "Returned", r.results)
      if (r.events.isNotEmpty()) section("Events", r.events)
    }

    private fun StringBuilder.row(label: String, value: String) {
      append("<br><span style='color:${grey()}'>$label</span>&nbsp; <code>${esc(value)}</code>")
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
      append("<br><span style='color:${grey()}'>$title</span>")
      lines.forEach { append("<br>&nbsp;&nbsp;<code>${esc(it)}</code>") }
    }
  }
}
