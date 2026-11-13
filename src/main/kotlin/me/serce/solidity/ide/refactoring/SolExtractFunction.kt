package me.serce.solidity.ide.refactoring

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import me.serce.solidity.ide.intentions.indentAt
import me.serce.solidity.ide.intentions.indentUnit
import me.serce.solidity.lang.psi.*

private const val EXTRACT = "Extract Function"

/**
 * Extract Function (Ctrl/Cmd+Alt+M): moves the selected statements into a new private function placed after the
 * current one, and calls it in their place.
 *
 * Local variables the statements read become parameters. Variables they declare and that are used afterwards, and
 * outer variables they assign that are used anywhere else, become return values. Statements with `return`, `_`, or
 * `break`/`continue` leaving the selection can't be extracted.
 */
class SolExtractFunctionHandler : RefactoringActionHandler {
  /** Overrides the name prompt (tests). */
  var nameForTests: String? = null

  override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val statements = selectedStatements(editor, file)
      ?: return fail(project, editor, "Select whole statements inside a function body")
    val plan = try {
      plan(statements)
    } catch (e: CannotExtract) {
      return fail(project, editor, e.message!!)
    }
    val name = nameForTests ?: if (ApplicationManager.getApplication().isUnitTestMode) plan.defaultName else askName(project, plan.defaultName)
    name ?: return
    apply(project, editor, plan, name)
  }

  override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

  private class CannotExtract(message: String) : Exception(message)

  private class Variable(val declaration: PsiNamedElement, val name: String, val type: String)

  private class Plan(
    val statements: List<SolStatement>,
    val callable: PsiElement,
    val inputs: List<Variable>,
    /** Outer variables the statements only assign: declared at the start of the new function. */
    val locals: List<Variable>,
    /** Return values: variables declared by the statements, and outer variables they assign. */
    val outputs: List<Variable>,
    val declaredOutputs: Set<PsiNamedElement>,
    val mutability: String?,
    val inContract: Boolean,
    val unchecked: Boolean,
    val defaultName: String,
  )

  /** The statements of one block the selection covers, or the outermost statement at the caret. */
  private fun selectedStatements(editor: Editor, file: PsiFile): List<SolStatement>? {
    val selection = editor.selectionModel
    val text = editor.document.charsSequence
    if (!selection.hasSelection()) {
      val at = file.findElementAt(editor.caretModel.offset) ?: return null
      var st = PsiTreeUtil.getParentOfType(at, SolStatement::class.java, false) ?: return null
      while (!inBlock(st)) st = PsiTreeUtil.getParentOfType(st, SolStatement::class.java, true) ?: return null
      return listOf(st)
    }
    var start = selection.selectionStart
    var end = selection.selectionEnd
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    val first = outermostStatementStartingAt(file, start) ?: return null
    val block = first.parent
    val statements = PsiTreeUtil.getChildrenOfTypeAsList(block, SolStatement::class.java)
      .filter { it.textRange.startOffset >= start && it.textRange.endOffset <= end }
    if (statements.isEmpty() || statements.last().textRange.endOffset != end) return null
    return statements
  }

  private fun inBlock(st: SolStatement) = st.parent is SolBlock || st.parent is SolUncheckedBlock

  private fun outermostStatementStartingAt(file: PsiFile, offset: Int): SolStatement? {
    val leaf = file.findElementAt(offset) ?: return null
    var result: SolStatement? = null
    var st = PsiTreeUtil.getParentOfType(leaf, SolStatement::class.java, false)
    while (st != null && st.textRange.startOffset == offset) {
      if (inBlock(st)) result = st
      st = PsiTreeUtil.getParentOfType(st, SolStatement::class.java, true)
    }
    return result
  }

  private fun plan(statements: List<SolStatement>): Plan {
    val callable = PsiTreeUtil.getParentOfType(
      statements.first(), SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolConstructorDefinition::class.java,
    ) ?: throw CannotExtract("Select statements inside a function body")
    val range = TextRange(statements.first().textRange.startOffset, statements.last().textRange.endOffset)
    val inside = statements.flatMap { st -> PsiTreeUtil.findChildrenOfType(st, PsiElement::class.java).toList() + st }

    if (inside.any { it is SolReturnSt || it is SolReturnTupleStatement }) throw CannotExtract("The statements contain 'return'")
    if (inside.any { it is SolPlaceholderStatement }) throw CannotExtract("The statements contain the modifier placeholder '_'")
    inside.filter { it is SolBreakSt || it is SolContinueSt }.forEach { jump ->
      val loop = PsiTreeUtil.getParentOfType(jump, SolForStatement::class.java, SolWhileStatement::class.java, SolDoWhileStatement::class.java)
      if (loop == null || !range.contains(loop.textRange)) throw CannotExtract("The statements contain 'break' or 'continue' for a loop around them")
    }

    // every local of the callable (parameters, named returns, variables), by what refers to it
    val locals = PsiTreeUtil.findChildrenOfAnyType(callable, SolParameterDef::class.java, SolVariableDeclaration::class.java, SolTypedDeclarationItem::class.java)
      .filter { (it as PsiNamedElement).name != null }
      .map { it as PsiNamedElement }
      .toSet()
    val refs = PsiTreeUtil.findChildrenOfType(callable, PsiElement::class.java)
      .filter { it is SolReferenceElement }
      .mapNotNull { ref -> (ref.reference?.resolve() as? PsiNamedElement)?.takeIf { it in locals }?.let { ref to it } }
    val refsInside = refs.filter { range.contains(it.first.textRange) }
    val refsOutside = refs.filter { !range.contains(it.first.textRange) }

    val declaredInside = locals.filter { range.contains(it.textRange) }.toSet()
    val returnParams = (callable as? SolFunctionDefinition)?.returns?.parameterDefList.orEmpty().toSet()
    val referenced = refsInside.map { it.second }.filter { it !in declaredInside }.distinct()
    val written = refsInside.filter { isWrite(it.first) }.map { it.second }.toSet()
    val read = refsInside.filter { !isWrite(it.first) }.map { it.second }.toSet()
    val usedOutside = refsOutside.map { it.second }.toSet()
    // where each variable first shows up in the statements: its declaration, or its first use
    fun firstAppearance(v: PsiNamedElement): Int = if (v in declaredInside) v.textOffset
      else refsInside.first { it.second == v }.first.textOffset
    val outputs = (declaredInside.filter { it in usedOutside } +
      referenced.filter { it in written && (it in usedOutside || it in returnParams) })
      .sortedBy { firstAppearance(it) }
    // a variable the statements only assign needs no argument: the new function declares it, unless it's a storage
    // pointer, which can't be declared without a value
    val writeOnly = referenced.filter { it !in read && !typeOf(it).endsWith(" storage") }
    val inputs = referenced - writeOnly.toSet()
    if (outputs.any { it in declaredInside && !declaredAtTopLevel(it, statements) }) {
      throw CannotExtract("A variable declared inside a nested block is used after the statements")
    }
    val mixedStorage = outputs.any { it !in declaredInside } && outputs.any { it in declaredInside && typeOf(it).endsWith(" storage") }
    if (mixedStorage) throw CannotExtract("A storage pointer declared by the statements can't be returned together with assigned variables")

    val contract = PsiTreeUtil.getParentOfType(callable, SolContractDefinition::class.java)
    val taken = contract?.functionDefinitionList?.mapNotNull { it.name }?.toSet()
      ?: PsiTreeUtil.getChildrenOfTypeAsList(callable.containingFile, SolFunctionDefinition::class.java).mapNotNull { it.name }.toSet()
    val defaultName = generateSequence(1) { it + 1 }.map { if (it == 1) "extracted" else "extracted$it" }.first { it !in taken }
    return Plan(
      statements = statements,
      callable = callable,
      inputs = inputs.map { Variable(it, it.name!!, typeOf(it)) },
      locals = writeOnly.filter { it in outputs }.map { Variable(it, it.name!!, typeOf(it)) },
      outputs = outputs.map { Variable(it, it.name!!, typeOf(it)) },
      declaredOutputs = outputs.filter { it in declaredInside }.toSet(),
      mutability = (callable as? SolFunctionDefinition)?.stateMutabilitySpecifierList?.map { it.text }?.firstOrNull { it == "pure" || it == "view" },
      inContract = contract != null,
      unchecked = statements.first().parent is SolUncheckedBlock,
      defaultName = defaultName,
    )
  }

  /** A declaration that a top-level statement of the selection makes, so it's visible after the selection. */
  private fun declaredAtTopLevel(declaration: PsiElement, statements: List<SolStatement>): Boolean {
    val statement = PsiTreeUtil.getParentOfType(declaration, SolStatement::class.java) ?: return false
    return statement in statements
  }

  /** `x = ...`, `x += ...`, `(x, y) = ...`, `x++`, `++x`, `delete x`, or Yul `x := ...`. */
  private fun isWrite(ref: PsiElement): Boolean {
    if (ref is SolYulPath) {
      val assignment = ref.parent as? SolYulAssignment ?: return false
      return ref in assignment.yulPathList
    }
    var expr: PsiElement = PsiTreeUtil.getParentOfType(ref, SolExpression::class.java, false) ?: return false
    // through tuple components: `(x, y) = ...`
    while (expr.parent is SolSeqExpression) expr = expr.parent
    val parent = expr.parent
    return when (parent) {
      is SolAssignmentExpression -> parent.expressionList.firstOrNull() == expr
      is SolTupleStatement -> expr.textRange.endOffset < (parent.node.findChildByType(me.serce.solidity.lang.core.SolidityTokenTypes.ASSIGN)?.startOffset ?: 0)
      is SolIncrementExpression -> true
      is SolUnaryExpression -> parent.firstChild.text.let { it == "++" || it == "--" || it == "delete" }
      else -> false
    }
  }

  /** The declared type of a local, with its data location. */
  private fun typeOf(declaration: PsiElement): String {
    val (type, location) = when (declaration) {
      is SolParameterDef -> declaration.typeName to declaration.storageLocationSpecifier
      is SolVariableDeclaration -> declaration.typeName to declaration.storageLocationSpecifier
      is SolTypedDeclarationItem -> declaration.typeName to declaration.storageLocationSpecifier
      else -> null to null
    }
    type ?: throw CannotExtract("The type of '${(declaration as PsiNamedElement).name}' isn't written out")
    return listOfNotNull(type.text, location?.text).joinToString(" ")
  }

  private fun apply(project: Project, editor: Editor, plan: Plan, name: String) {
    val document = editor.document
    val text = document.charsSequence
    val first = plan.statements.first()
    val last = plan.statements.last()
    val unit = indentUnit(text)
    val callableIndent = indentAt(text, plan.callable.textRange.startOffset)
    val statementIndent = indentAt(text, first.textRange.startOffset)
    val bodyIndent = callableIndent + unit + if (plan.unchecked) unit else ""

    // the statements, re-indented from where they were to the new body
    val lines = text.subSequence(first.textRange.startOffset, last.textRange.endOffset).toString().lines()
    val body = lines.mapIndexed { i, line ->
      when {
        line.isBlank() -> ""
        i == 0 -> bodyIndent + line
        line.startsWith(statementIndent) -> bodyIndent + line.removePrefix(statementIndent)
        else -> bodyIndent + line.trimStart()
      }
    }.toMutableList()
    plan.locals.asReversed().forEach { body.add(0, "$bodyIndent${it.type} ${it.name};") }
    when (plan.outputs.size) {
      0 -> {}
      1 -> body += "${bodyIndent}return ${plan.outputs[0].name};"
      else -> body += "${bodyIndent}return (${plan.outputs.joinToString(", ") { it.name }});"
    }
    if (plan.unchecked) {
      body.add(0, "$callableIndent${unit}unchecked {")
      body += "$callableIndent$unit}"
    }
    val header = buildString {
      append("function ").append(name)
      append(plan.inputs.joinToString(", ", "(", ")") { "${it.type} ${it.name}" })
      if (plan.inContract) append(" private")
      plan.mutability?.let { append(" ").append(it) }
      if (plan.outputs.isNotEmpty()) append(plan.outputs.joinToString(", ", " returns (", ")") { it.type })
    }
    val function = "\n\n$callableIndent$header {\n${body.joinToString("\n")}\n$callableIndent}"

    val call = "$name(${plan.inputs.joinToString(", ") { it.name }})"
    val declared = plan.outputs.filter { it.declaration in plan.declaredOutputs }
    val callStatement = when {
      plan.outputs.isEmpty() -> "$call;"
      declared.size == plan.outputs.size && plan.outputs.size == 1 -> "${declared[0].type} ${declared[0].name} = $call;"
      declared.size == plan.outputs.size -> plan.outputs.joinToString(", ", "(", ") = $call;") { "${it.type} ${it.name}" }
      else -> {
        val predeclared = declared.joinToString("") { "${it.type} ${it.name};\n$statementIndent" }
        val targets = plan.outputs.joinToString(", ") { it.name }
        predeclared + if (plan.outputs.size == 1) "$targets = $call;" else "($targets) = $call;"
      }
    }

    val callableEnd = plan.callable.textRange.endOffset
    val replaceRange = TextRange(first.textRange.startOffset, last.textRange.endOffset)
    WriteCommandAction.runWriteCommandAction(project, EXTRACT, null, {
      document.insertString(callableEnd, function)
      document.replaceString(replaceRange.startOffset, replaceRange.endOffset, callStatement)
      PsiDocumentManager.getInstance(project).commitDocument(document)
    })
    editor.selectionModel.removeSelection()
    editor.caretModel.moveToOffset(replaceRange.startOffset + callStatement.indexOf(call))
  }

  private fun askName(project: Project, default: String): String? =
    Messages.showInputDialog(project, "Function name:", EXTRACT, null, default, object : InputValidator {
      override fun checkInput(inputString: String) = inputString.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))
      override fun canClose(inputString: String) = checkInput(inputString)
    })

  private fun fail(project: Project, editor: Editor, message: String) =
    CommonRefactoringUtil.showErrorHint(project, editor, message, EXTRACT, null)
}
