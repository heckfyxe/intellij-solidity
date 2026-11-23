package me.serce.solidity.chain.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.Deployment
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.abi.AbiInputException
import me.serce.solidity.chain.abi.toHex
import me.serce.solidity.chain.abi.toWord
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.rpc.RpcException
import java.math.BigInteger
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Reads a contract's storage, like `cast storage`: any slot by number, and for contracts compiled in this project every
 * state variable decoded from its layout (packed values, strings, arrays with their first elements), plus mapping
 * entries, array elements and struct members reached by a key, an index or a path like `[0xBEEF][2].amount`.
 */
class StorageDialog(
  private val project: Project,
  private val chain: ChainConfig,
  private val deployment: Deployment?,
  address: String?,
) : DialogWrapper(project, false) {
  private val service = ChainService.getInstance(project)
  private val addressField = JBTextField(address.orEmpty())
  private val slotField = JBTextField("0")
  private val slotResult = JBLabel(" ").apply { setCopyable(true) }
  private val typesField = JBTextField()
  private val layoutStatus = JBLabel(" ")
  private val keyField = JBTextField()
  private val keyResult = JBLabel(" ").apply { setCopyable(true) }
  private val lookUpButton = JButton("Look Up")
  private var layout: StorageLayout? = null

  private class Line(val row: StorageLayout.Row, var value: String = "…")

  private val model = ListTableModel<Line>(
    column("Variable") { it.row.name },
    column("Type") { it.row.type.label },
    column("Slot") { it.row.slot.slotLabel() + if (it.row.offset > 0) " +${it.row.offset}" else "" },
    column("Value") { it.value },
  )
  // Cells are selected one by one so Copy takes just the selected values (a single slot's value), not whole rows.
  private val table = TableView(model).apply { cellSelectionEnabled = true }

  init {
    title = "Storage" + (deployment?.let { " of ${it.name}" } ?: "") + " on ${chain.name}"
    isModal = false
    slotField.emptyText.text = "5, 0x360894…"
    slotField.addActionListener { readSlot() }
    typesField.emptyText.text = "uint128, address, bool  (optional)"
    typesField.addActionListener { readSlot() }
    keyField.addActionListener { lookUp() }
    lookUpButton.addActionListener { lookUp() }
    table.selectionModel.addListSelectionListener { updateLookUp() }
    init()
    loadLayout()
    updateLookUp()
  }

  override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })

  override fun createCenterPanel(): JComponent = panel {
    row("Address:") { cell(addressField).align(AlignX.FILL) }
    row("Slot:") {
      cell(slotField).align(AlignX.FILL).resizableColumn()
      button("Read") { readSlot() }
    }
    row("Decode as:") {
      cell(typesField).align(AlignX.FILL)
        .comment("Value types packed in the slot, from its low-order end as solc packs them; one that doesn't fit continues in the next slot")
    }
    row("") { cell(slotResult) }
    if (deployment?.sourcePath?.isNotEmpty() == true) {
      group("Layout") {
        row { cell(layoutStatus) ; button("Reload Values") { readAll() } }
        row { cell(JBScrollPane(table)).align(Align.FILL) }.resizableRow()
        row("Key / index:") {
          cell(keyField).align(AlignX.FILL).resizableColumn()
            .comment("Select a mapping or an array above, then enter a key or an index; go deeper with a path like [1][0xBEEF].amount")
          cell(lookUpButton)
        }
        row("") { cell(keyResult) }
      }.resizableRow()
    } else {
      row { comment("Variables are listed for contracts compiled in this project; for others read slots by number.") }
    }
  }.apply { preferredSize = JBUI.size(760, if (deployment?.sourcePath?.isNotEmpty() == true) 560 else 160) }

  private fun address(): String? = addressField.text.trim().takeIf {
    val hex = it.removePrefix("0x")
    hex.length == 40 && hex.all { c -> Character.digit(c, 16) >= 0 }
  }?.let { AbiCodec.checksum(it) }

  private fun reader(): StorageReader? {
    val rpc = service.runtime(chain.id).rpc ?: return null
    val address = address() ?: return null
    return StorageReader { slot -> rpc.storageAt(address, slot.toHex32()).toWord() }
  }

  /** Runs [read] off the EDT and shows what it returns, or its error, with [show] on the EDT. */
  private fun background(read: () -> String, show: (String) -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val text = try {
        read()
      } catch (e: RpcException) {
        "error: ${e.message}"
      } catch (e: AbiInputException) {
        "error: ${e.message}"
      }
      ApplicationManager.getApplication().invokeLater({ show(text) }, ModalityState.any())
    }
  }

  private fun problem(): String? = when {
    service.runtime(chain.id).rpc == null -> "${chain.name} is not connected"
    address() == null -> "Enter the contract's address"
    else -> null
  }

  private fun readSlot() {
    problem()?.let { slotResult.text = it; return }
    val slot = try {
      StorageLayout.parseSlot(slotField.text)
    } catch (e: AbiInputException) {
      slotResult.text = e.message
      return
    }
    val reader = reader() ?: return
    val types = typesField.text
    slotResult.text = "reading…"
    background({
      if (types.isBlank()) describeWord(reader.word(slot))
      else "<html>" + StorageLayout.decodePacked(types, slot, reader).joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
    }) { slotResult.text = it }
  }

  private fun loadLayout() {
    val d = deployment?.takeIf { it.sourcePath.isNotEmpty() } ?: return
    val foundry = FoundryProject.find(project) ?: run { layoutStatus.text = "No Foundry project"; return }
    layoutStatus.text = "Reading the layout with forge inspect…"
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching { StorageLayout.parse(foundry.storageLayout(d.sourcePath, d.name)) }
      ApplicationManager.getApplication().invokeLater({
        result.onSuccess {
          layout = it
          model.items = it.rows().map { r -> Line(r) }
          layoutStatus.text = if (it.variables.isEmpty()) "${d.name} has no state variables" else "${it.variables.size} state variables"
          readAll()
        }.onFailure { layoutStatus.text = "No layout: ${it.message}" }
      }, ModalityState.any())
    }
  }

  private fun readAll() {
    val layout = layout ?: return
    problem()?.let { layoutStatus.text = it; return }
    val reader = reader() ?: return
    val lines = model.items
    ApplicationManager.getApplication().executeOnPooledThread {
      val values = lines.map { line ->
        try {
          layout.read(line.row, reader)
        } catch (e: RpcException) {
          "error: ${e.message}"
        }
      }
      ApplicationManager.getApplication().invokeLater({
        lines.zip(values).forEach { (line, value) -> line.value = value }
        model.fireTableDataChanged()
      }, ModalityState.any())
    }
  }

  /** The selected variable when it can be looked into: a mapping or an array, fixed or dynamic. */
  private fun selectedContainer(): StorageLayout.Row? = table.selectedObject?.row?.takeIf {
    it.type.encoding == "mapping" || it.type.encoding == "dynamic_array" || it.type.base != null
  }

  private fun updateLookUp() {
    val row = selectedContainer()
    lookUpButton.isEnabled = row != null
    keyField.isEnabled = row != null
    keyField.emptyText.text = when {
      row == null -> ""
      row.type.encoding == "mapping" -> layout?.type(row.type.key.orEmpty())?.label.orEmpty()
      else -> "index"
    }
  }

  private fun lookUp() {
    val row = selectedContainer() ?: return
    val layout = layout ?: return
    problem()?.let { keyResult.text = it; return }
    val reader = reader() ?: return
    val path = keyField.text
    keyResult.text = "reading…"
    background({
      val shown = StorageLayout.parsePath(path).joinToString("")
      val loc = layout.access(row, path, reader)
      val at = "slot ${loc.slot.slotLabel()}" + if (loc.offset > 0) " +${loc.offset}" else ""
      "${row.name}$shown = ${layout.read(loc, reader)}   ($at)"
    }) { keyResult.text = it }
  }

  private fun column(name: String, value: (Line) -> String) = object : ColumnInfo<Line, String>(name) {
    override fun valueOf(item: Line) = value(item)
  }

  companion object {
    /** A raw word as hex, and as the number or address it most likely holds. */
    fun describeWord(word: ByteArray): String {
      val n = BigInteger(1, word)
      val parts = mutableListOf(word.toHex())
      if (n.signum() != 0) parts += "uint $n"
      if (n.signum() != 0 && word.take(12).all { it.toInt() == 0 } && n.bitLength() > 64) parts += "address ${AbiCodec.checksum(word.copyOfRange(12, 32).toHex())}"
      return parts.joinToString("   ")
    }
  }
}
