package me.serce.solidity.ide.generation

import javax.swing.JComponent
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.ide.util.MemberChooser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import me.serce.solidity.ide.indentAt
import me.serce.solidity.ide.indentUnit
import me.serce.solidity.ide.inspections.fixes.AddVirtualFix
import me.serce.solidity.ide.inspections.fixes.SolSpecifierEditor
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.ContractType
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolNamedElement
import me.serce.solidity.lang.psi.parentOfType
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.resolve.function.SolOverrideResolver
import me.serce.solidity.lang.resolve.function.SolPublicGetter
import javax.swing.JCheckBox
import javax.swing.JTree

/** Shared logic of Code | Override Methods (Ctrl+O) and Code | Implement Methods (Ctrl+I) for Solidity. */
abstract class SolOverrideImplementHandler : LanguageCodeInsightActionHandler {
  protected abstract val title: String

  /** Whether a not-yet-overridden base member is offered by this action. */
  protected abstract fun accept(member: SolNamedElement): Boolean

  /** Hint shown when the contract has bases but nothing to offer. */
  protected abstract val nothingFoundMessage: String

  /** Whether the chooser offers implementing external getters with public state variables. */
  protected open val offersGetterVariables = false

  override fun startInWriteAction() = false

  override fun isValidFor(editor: Editor, file: PsiFile): Boolean {
    // available whenever the contract inherits something, so an empty result can be explained with a hint
    return contractAt(editor, file)?.supers?.isNotEmpty() == true
  }

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val contract = contractAt(editor, file) ?: return
    val candidates = candidates(contract)
    if (candidates.isEmpty()) {
      HintManager.getInstance().showErrorHint(editor, nothingFoundMessage)
      return
    }
    val choice = choose(project, candidates) ?: return
    val chosen = choice.members
    if (chosen.isEmpty()) return
    // non-virtual bases get `virtual` added, possibly in other files
    val files = (listOf(file) + chosen.mapNotNull { it.containingFile }).distinct().toTypedArray()
    WriteCommandAction.runWriteCommandAction(project, title, null, { insert(editor, contract, chosen, choice.getterVariables) }, *files)
  }

  /** The contract whose body contains the caret; libraries can't inherit, so they are excluded. */
  internal fun contractAt(editor: Editor, file: PsiFile): SolContractDefinition? {
    val offset = editor.caretModel.offset
    val element = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    val contract = element.parentOfType<SolContractDefinition>(strict = false) ?: return null
    if (contract.contractType == ContractType.LIBRARY) return null
    val lbrace = contract.node.findChildByType(SolidityTokenTypes.LBRACE) ?: return null
    return if (offset >= lbrace.textRange.endOffset) contract else null
  }

  /** Most derived base declaration per signature, not yet redefined in [contract], filtered by [accept]. */
  internal fun candidates(contract: SolContractDefinition): List<SolNamedElement> {
    val parents = SolOverrideResolver.linearizedParents(contract) ?: return emptyList()
    val own = SolOverrideResolver.overridableMembers(contract)
    val mostDerived = mutableListOf<SolNamedElement>()
    for (parent in parents) {
      for (member in SolOverrideResolver.overridableMembers(parent)) {
        if (!SolOverrideResolver.participates(member)) continue
        if (mostDerived.any { SolOverrideResolver.sameSignature(it, member) }) continue
        mostDerived += member
      }
    }
    // a public state variable whose getter matches implements the function for good (variables can't be overridden)
    return mostDerived.filter { m ->
      own.none { SolOverrideResolver.sameSignature(it, m) } && accept(m) &&
        (m !is SolFunctionDefinition || SolPublicGetter.implementingVariable(contract, m) == null)
    }
  }

  /** Chosen members, and whether getters become public state variables (see [offersGetterVariables]). */
  class Choice(val members: List<SolNamedElement>, val getterVariables: Boolean)

  /** Shows the member chooser; returns null when cancelled. In tests every candidate is chosen. */
  protected open fun choose(project: Project, candidates: List<SolNamedElement>): Choice? {
    val properties = PropertiesComponent.getInstance(project)
    val getterVariables = offersGetterVariables && properties.getBoolean(PROP_GETTER_VARIABLES, true)
    if (ApplicationManager.getApplication().isUnitTestMode) return Choice(candidates, getterVariables)
    // the tree renders on the EDT without a read action, so everything shown is computed here
    val objects = ReadAction.compute<Array<SolMemberChooserObject>, RuntimeException> {
      candidates.map { SolMemberChooserObject(it) }.toTypedArray()
    }
    val checkbox = JCheckBox("Implement getters as public state variables", getterVariables)
    val options: Array<JComponent> = if (offersGetterVariables) arrayOf(checkbox) else emptyArray()
    val chooser = MemberChooser(objects, false, true, project, null, options)
    chooser.title = title
    chooser.show()
    if (chooser.exitCode != DialogWrapper.OK_EXIT_CODE) return null
    if (offersGetterVariables) properties.setValue(PROP_GETTER_VARIABLES, checkbox.isSelected, true)
    return Choice(chooser.selectedElements?.map { it.member } ?: emptyList(), offersGetterVariables && checkbox.isSelected)
  }

  private fun insert(editor: Editor, contract: SolContractDefinition, members: List<SolNamedElement>, getterVariables: Boolean) {
    val project = contract.project
    // bases that must become virtual; pointers survive the reparse caused by inserting the stubs
    val pointerManager = SmartPointerManager.getInstance(project)
    val toMakeVirtual = members.filter { !SolOverrideResolver.isVirtual(it) }.map { pointerManager.createSmartPsiElementPointer(it) }
    val document = editor.document
    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)

    // indent ourselves: with an external formatter (Foundry) IntelliJ's formatter is disabled for Solidity
    val unit = indentUnit(contract.containingFile)
    val contractIndent = indentAt(document, contract.textRange.startOffset)
    val baseIndent = contractIndent + unit
    val (variables, functions) = members.partition {
      getterVariables && it is SolFunctionDefinition && !SolOverrideResolver.hasBody(it) && SolPublicGetter.canImplementAsVariable(it, contract)
    }
    fun override(m: SolNamedElement): String = overrideText(contract, SolOverrideResolver.visibleDeclaringBases(contract, m).mapNotNull { it.name })
    // variables first, one per line, then the functions separated by blank lines
    val variableText = variables.joinToString("\n") { baseIndent + SolPublicGetter.variableText(it as SolFunctionDefinition, override(it)) }
    val functionText = functions.joinToString("\n\n") { m ->
      val stub = SolOverrideStubRenderer.render(m, override(m))
      val lines = stub.lines()
      lines.mapIndexed { i, l -> if (i == 0 || i == lines.lastIndex) baseIndent + l else baseIndent + unit + l }
        .joinToString("\n")
    }
    val text = listOf(variableText, functionText).filter { it.isNotEmpty() }.joinToString("\n\n")

    var offset = insertionOffset(editor, contract)
    val line = document.getLineNumber(offset)
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val inserted: String
    if (document.charsSequence.subSequence(lineStart, lineEnd).isBlank()) {
      // an empty line: replace it with the generated members
      document.deleteString(lineStart, lineEnd)
      offset = lineStart
      inserted = text
    } else if (document.charsSequence.subSequence(lineStart, offset).isBlank()) {
      // only indentation before the offset (e.g. before the closing brace)
      document.deleteString(lineStart, offset)
      offset = lineStart
      inserted = "$text\n$contractIndent"
    } else {
      inserted = "\n$text\n"
    }
    document.insertString(offset, inserted)
    psiDocumentManager.commitDocument(document)
    val marker = document.createRangeMarker(offset, offset + inserted.length)
    // place the caret at the start of the first generated member
    val firstLine = document.getLineNumber(marker.startOffset) + if (inserted.startsWith("\n")) 1 else 0
    if (firstLine < document.lineCount) {
      val start = document.getLineStartOffset(firstLine)
      val lineText = document.charsSequence.subSequence(start, document.getLineEndOffset(firstLine))
      editor.caretModel.moveToOffset(start + lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
    }
    marker.dispose()

    for (pointer in toMakeVirtual) {
      pointer.element?.let { SolSpecifierEditor.addVirtual(it) }
    }
  }

  /**
   * `override`, or `override(A, B)` for several bases; nothing when the pragma allows only solc before 0.6.0, which
   * has no `override` (it is required from 0.6.0 and optional for a single interface since 0.8.8, so always added).
   */
  private fun overrideText(contract: SolContractDefinition, bases: List<String>): String {
    val range = contract.containingFile.solidityVersionRange()
    return if (range != null && range.isBelow(SolPublicGetter.OVERRIDE_SINCE)) "" else SolSpecifierEditor.overrideText(bases)
  }

  /** At the caret when it is between members of [contract]; after the member under the caret; else before `}`. */
  private fun insertionOffset(editor: Editor, contract: SolContractDefinition): Int {
    val rbrace = contract.node.findChildByType(SolidityTokenTypes.RBRACE)?.startOffset ?: contract.textRange.endOffset
    val lbraceEnd = contract.node.findChildByType(SolidityTokenTypes.LBRACE)?.textRange?.endOffset ?: return rbrace
    val caret = editor.caretModel.offset
    if (caret < lbraceEnd || caret > rbrace) return rbrace
    val member = contract.children.firstOrNull { it.textRange.startOffset < caret && caret < it.textRange.endOffset }
    return member?.textRange?.endOffset ?: caret
  }
}

private const val PROP_GETTER_VARIABLES = "solidity.implement.getters.as.variables"

class SolOverrideMethodsHandler : SolOverrideImplementHandler() {
  override val title = "Override Functions"
  override val nothingFoundMessage = "No functions to override"

  // non-virtual members are offered too and made virtual on insert, as long as their file can be edited
  override fun accept(member: SolNamedElement) = SolOverrideResolver.isVirtual(member) || AddVirtualFix.canEdit(member)
}

class SolImplementMethodsHandler : SolOverrideImplementHandler() {
  override val title = "Implement Functions"
  override val nothingFoundMessage = "No functions to implement"
  override val offersGetterVariables = true
  override fun accept(member: SolNamedElement) = member is SolFunctionDefinition && !SolOverrideResolver.hasBody(member)
}

/** Text, icon and parent are computed on creation: the chooser renders on the EDT outside a read action. */
private class SolMemberChooserObject(val member: SolNamedElement) : ClassMember {
  private val parent = SolOverrideResolver.containingContract(member)?.let { SolContractChooserObject(it) }
  private val text = "${SolOverrideResolver.displayName(member)}${SolOverrideResolver.parametersText(member) ?: "()"}"
  private val virtual = SolOverrideResolver.isVirtual(member)
  private val icon = member.getIcon(0)

  override fun getParentNodeDelegate(): MemberChooserObject? = parent

  override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree?) {
    component.append(text)
    if (!virtual) {
      component.append("  will be made virtual", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
    component.icon = icon
  }

  override fun getText(): String = text

  override fun equals(other: Any?) = other is SolMemberChooserObject && other.member == member
  override fun hashCode() = member.hashCode()
}

private class SolContractChooserObject(val contract: SolContractDefinition) :
  MemberChooserObjectBase(contract.name ?: "", contract.getIcon(0)) {
  override fun equals(other: Any?) = other is SolContractChooserObject && other.contract == contract
  override fun hashCode() = contract.hashCode()
}
