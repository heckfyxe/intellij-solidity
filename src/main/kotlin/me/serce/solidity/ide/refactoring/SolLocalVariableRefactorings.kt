package me.serce.solidity.ide.refactoring

import com.intellij.codeInsight.unwrap.ScopeHighlighter
import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil
import me.serce.solidity.lang.SolidityLanguage
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.*

private const val INTRODUCE = "Introduce Variable"
private const val INLINE = "Inline Variable"

/** The Solidity text of a local variable's type for a value of [type], or null when it can't be declared. */
internal fun declarationType(type: SolType, expression: SolExpression): String? {
  fun location(): String = when (rootDeclaration(expression)) {
    is SolStateVariableDeclaration -> "storage"
    is SolParameterDef -> (rootDeclaration(expression) as SolParameterDef).storageLocationSpecifier?.text ?: "memory"
    else -> "memory"
  }
  return when (type) {
    is SolInteger -> if (type.literalValue != null) (if (type.literalValue.signum() < 0) "int256" else "uint256") else type.toString()
    is SolBoolean, is SolAddress, is SolFixedBytes, is SolFixedByte -> type.toString()
    is SolContract -> type.ref.name
    is SolEnum -> type.ref.name
    is SolUserDefinedValueTypeReferenceType -> type.ref.name
    is SolString -> "string ${location()}"
    is SolArray -> declarationType(type.type, expression)?.substringBefore(' ')?.let {
      (if (type is SolArray.SolStaticArray) "$it[${type.size}]" else if (type == SolBytes) "bytes" else "$it[]") + " " + location()
    }
    is SolStruct -> "${type.ref.name} ${location()}"
    is SolMapping -> if (location() == "storage") "${mappingText(type) ?: return null} storage" else null
    else -> null
  }
}

private fun mappingText(type: SolType): String? = when (type) {
  is SolMapping -> mappingText(type.from)?.let { from -> mappingText(type.to)?.let { to -> "mapping($from => $to)" } }
  is SolInteger, is SolBoolean, is SolAddress, is SolFixedBytes, is SolString -> type.toString()
  is SolStruct -> type.ref.name
  is SolContract -> type.ref.name
  is SolEnum -> type.ref.name
  is SolArray -> mappingText(type.type)?.let { if (type is SolArray.SolStaticArray) "$it[${type.size}]" else "$it[]" }
  else -> null
}

/** The variable an lvalue-like expression (`s.a[i].b`) starts from. */
private fun rootDeclaration(expression: SolExpression): PsiElement? {
  var current: PsiElement = expression
  while (true) {
    current = when (current) {
      is SolMemberAccessExpression -> current.expression
      is SolIndexAccessExpression -> current.expressionList.firstOrNull() ?: return null
      is SolPrimaryExpression -> return current.varLiteral?.reference?.resolve()
      else -> return null
    }
  }
}

/** A name for a variable holding [expression]: the called function or accessed member, minus a `get` prefix. */
internal fun suggestName(expression: SolExpression): String {
  val base = when (expression) {
    is SolFunctionCallExpression -> expression.referenceName
    is SolMemberAccessExpression -> expression.identifier?.text
    is SolIndexAccessExpression -> (expression.expressionList.firstOrNull() as? SolPrimaryExpression)?.varLiteral?.text?.let { "${it.removeSuffix("s")}Item" }
    else -> null
  }?.takeIf { it.isNotEmpty() && it.first().isLetter() } ?: "value"
  val name = base.removePrefix("get").ifEmpty { base }
  return name.replaceFirstChar { it.lowercaseChar() }
}

/** Introduce Variable (Ctrl/Cmd+Alt+V): declares a local for the selected expression, right before its statement. */
class SolIntroduceVariableHandler : RefactoringActionHandler {
  override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
    val candidates = candidates(editor, file)
    when {
      candidates.isEmpty() -> fail(project, editor, "Select an expression inside a function body")
      candidates.size == 1 || ApplicationManager.getApplication().isUnitTestMode -> introduce(project, editor, candidates.first())
      else -> IntroduceTargetChooser.showChooser(
        editor, candidates, com.intellij.openapi.util.Pass.create { introduce(project, editor, it) },
        { it.text }, "Expressions", 0, ScopeHighlighter.NATURAL_RANGER
      )
    }
  }

  override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

  /** The selected expression, or the expressions at the caret from the innermost out. */
  private fun candidates(editor: Editor, file: PsiFile): List<SolExpression> {
    val selection = editor.selectionModel
    if (selection.hasSelection()) {
      val start = file.findElementAt(selection.selectionStart) ?: return emptyList()
      var expr = PsiTreeUtil.getParentOfType(start, SolExpression::class.java, false)
      while (expr != null && expr.textRange.endOffset < selection.selectionEnd) {
        expr = PsiTreeUtil.getParentOfType(expr, SolExpression::class.java, true)
      }
      return listOfNotNull(expr?.takeIf { it.textRange.startOffset >= selection.selectionStart - 1 && isExtractable(it) })
    }
    val offset = editor.caretModel.offset
    val at = file.findElementAt(offset)?.takeIf { it.textRange.startOffset < offset || offset == 0 } ?: file.findElementAt(offset - 1)
    return generateSequence(PsiTreeUtil.getParentOfType(at, SolExpression::class.java, false)) {
      PsiTreeUtil.getParentOfType(it, SolExpression::class.java, true)
    }.filter { isExtractable(it) }.toList()
  }

  private fun isExtractable(expr: SolExpression): Boolean {
    val statement = PsiTreeUtil.getParentOfType(expr, SolStatement::class.java) ?: return false
    if (statement.parent !is SolBlock) return false
    // the left side of an assignment, or a whole expression statement such as `f();`, isn't a value to extract
    val parent = expr.parent
    if (parent is SolAssignmentExpression && parent.expressionList.firstOrNull() == expr) return false
    if (parent is SolStatement) return false
    if (parent is SolFunctionCallExpression && parent.expression == expr) return false
    return true
  }

  private fun introduce(project: Project, editor: Editor, expression: SolExpression) {
    val type = declarationType(inferExprType(expression), expression)
      ?: return fail(project, editor, "Can't declare a variable of type ${inferExprType(expression)}")
    val statement = PsiTreeUtil.getParentOfType(expression, SolStatement::class.java)!!
    val document = editor.document
    val text = document.charsSequence
    val name = uniqueName(suggestName(expression), statement)
    var lineStart = statement.textRange.startOffset
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    val indent = text.subSequence(lineStart, statement.textRange.startOffset).toString()
    val declaration = "$type $name = ${expression.text};\n$indent"
    val exprRange = expression.textRange
    val insertAt = statement.textRange.startOffset
    WriteCommandAction.runWriteCommandAction(project, INTRODUCE, null, {
      document.replaceString(exprRange.startOffset, exprRange.endOffset, name)
      document.insertString(insertAt, declaration)
      PsiDocumentManager.getInstance(project).commitDocument(document)
    })
    val nameOffset = insertAt + type.length + 1
    editor.caretModel.moveToOffset(nameOffset)
    editor.selectionModel.removeSelection()
    if (!ApplicationManager.getApplication().isUnitTestMode) {
      val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
      val variable = PsiTreeUtil.getParentOfType(file.findElementAt(nameOffset), SolVariableDeclaration::class.java) ?: return
      VariableInplaceRenamer(variable, editor).performInplaceRename()
    }
  }

  /** [base], or `base2`, `base3`... when a variable visible at [statement] already has that name. */
  private fun uniqueName(base: String, statement: PsiElement): String {
    val function = PsiTreeUtil.getParentOfType(statement, SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolConstructorDefinition::class.java)
    val contract = PsiTreeUtil.getParentOfType(statement, SolContractDefinition::class.java)
    val taken = buildSet {
      function?.let { f -> PsiTreeUtil.findChildrenOfType(f, SolNamedElement::class.java).mapNotNullTo(this) { it.name } }
      contract?.stateVariableDeclarationList?.mapNotNullTo(this) { it.name }
    }
    if (base !in taken && base !in RESERVED) return base
    return generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
  }

  private fun fail(project: Project, editor: Editor, message: String) =
    CommonRefactoringUtil.showErrorHint(project, editor, message, INTRODUCE, null)

  private companion object {
    val RESERVED = setOf("this", "super", "now")
  }
}

/** Inline Variable: replaces the uses of a local variable with its initializer and removes its declaration. */
class SolInlineVariableHandler : InlineActionHandler() {
  override fun isEnabledForLanguage(l: Language?) = l == SolidityLanguage

  override fun canInlineElement(element: PsiElement?): Boolean {
    val declaration = element as? SolVariableDeclaration ?: return false
    return definitionOf(declaration) != null
  }

  /** The `T x = init;` statement of a single local variable. */
  private fun definitionOf(declaration: SolVariableDeclaration): SolVariableDefinition? {
    if (declaration.identifier == null) return null
    val definition = declaration.parent as? SolVariableDefinition ?: return null
    if (definition.expression == null) return null
    val statement = definition.parent as? SolStatement ?: return null
    return definition.takeIf { statement.parent is SolBlock }
  }

  override fun inlineElement(project: Project, editor: Editor?, element: PsiElement) {
    val declaration = element as SolVariableDeclaration
    val definition = definitionOf(declaration) ?: return
    val initializer = definition.expression!!
    val statement = definition.parent as SolStatement
    val block = statement.parent
    val references = ReferencesSearch.search(declaration, LocalSearchScope(block)).findAll().map { it.element }
    val written = references.firstOrNull { ref ->
      val expr = PsiTreeUtil.getParentOfType(ref, SolExpression::class.java, false)
      val parent = expr?.parent
      (parent is SolAssignmentExpression && parent.expressionList.firstOrNull() == expr) ||
        (parent is SolIncrementExpression)
    }
    if (written != null) {
      CommonRefactoringUtil.showErrorHint(project, editor, "Variable '${declaration.name}' is assigned after its declaration", INLINE, null)
      return
    }
    val replacement = if (needsParentheses(initializer)) "(${initializer.text})" else initializer.text
    val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
    WriteCommandAction.runWriteCommandAction(project, INLINE, null, {
      val ranges = references.map { PsiTreeUtil.getParentOfType(it, SolPrimaryExpression::class.java, false) ?: it }
        .map { it.textRange }.sortedByDescending { it.startOffset }
      ranges.forEach { document.replaceString(it.startOffset, it.endOffset, replacement) }
      // the declaration's whole line when it's alone on it
      val text = document.charsSequence
      var start = statement.textRange.startOffset
      var end = statement.textRange.endOffset
      var lineStart = start
      while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
      var lineEnd = end
      while (lineEnd < text.length && (text[lineEnd] == ' ' || text[lineEnd] == '\t')) lineEnd++
      if ((lineStart == 0 || text[lineStart - 1] == '\n') && lineEnd < text.length && text[lineEnd] == '\n') {
        start = lineStart
        end = lineEnd + 1
      }
      document.deleteString(start, end)
      PsiDocumentManager.getInstance(project).commitDocument(document)
    })
  }

  private fun needsParentheses(e: SolExpression) = e !is SolPrimaryExpression && e !is SolFunctionCallExpression &&
    e !is SolMemberAccessExpression && e !is SolIndexAccessExpression && e !is SolSeqExpression
}
