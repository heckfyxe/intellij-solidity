package me.serce.solidity.ide.intentions

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateExpressionSelectorBase
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.codeInsight.template.postfix.templates.StringBasedPostfixTemplate
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.*

/** Every expression ending at the caret, innermost first: `a + b.not` offers `b` and `a + b`. */
private class SolExpressionSelector(condition: Condition<PsiElement> = Condition { true }) :
  PostfixTemplateExpressionSelectorBase(condition) {
  override fun getNonFilteredExpressions(context: PsiElement, document: Document, offset: Int): List<PsiElement> {
    val result = mutableListOf<PsiElement>()
    var current: PsiElement? = PsiTreeUtil.getNonStrictParentOfType(context, SolExpression::class.java)
    while (current is SolExpression && current.textRange.endOffset == offset) {
      result += current
      current = current.parent
    }
    return result
  }

  override fun getRenderer() = com.intellij.util.Function<PsiElement, String> { it.text }
}

/** An expression that starts a statement inside a function: only those can become `if`, `require` or `return`. */
private fun startsStatement(element: PsiElement): Boolean {
  val statement = PsiTreeUtil.getParentOfType(element, SolStatement::class.java) ?: return false
  return statement.parent is SolBlock && statement.textRange.startOffset == element.textRange.startOffset
}

private fun indentOf(element: PsiElement): String {
  val text = element.containingFile.viewProvider.document?.charsSequence ?: element.containingFile.text
  var start = element.textRange.startOffset
  while (start > 0 && text[start - 1] != '\n') start--
  return text.subSequence(start, element.textRange.startOffset).takeWhile { it == ' ' || it == '\t' }.toString()
}

/** `!` binds tighter than any binary operator: those operands need parentheses. */
private fun needsParens(element: PsiElement) = element !is SolPrimaryExpression && element !is SolFunctionCallExpression &&
  element !is SolMemberAccessExpression && element !is SolIndexAccessExpression && element !is SolSeqExpression

/** A template that keeps the text as written: the plugin has no formatter of its own, and forge must not run here. */
private open class SolTemplate(
  name: String,
  example: String,
  provider: PostfixTemplateProvider,
  statement: Boolean,
  private val template: (expr: PsiElement) -> String,
) : StringBasedPostfixTemplate(name, example, SolExpressionSelector(if (statement) Condition { startsStatement(it) } else Condition { true }), provider) {
  private val statement = statement

  override fun getTemplateString(element: PsiElement) = template(element)
  override fun shouldReformat() = false

  /** A statement template replaces the whole statement, including the `;` [SolPostfixTemplateProvider] may add. */
  override fun getElementToRemove(expr: PsiElement): PsiElement =
    if (statement) PsiTreeUtil.getParentOfType(expr, SolStatement::class.java) ?: expr else expr

  /** Replaces the text directly: the templates have no variables to fill in, only the caret position. */
  override fun expandForChooseExpression(expr: PsiElement, editor: Editor) {
    val target = getElementToRemove(expr)
    val filled = getTemplateString(expr).replace("\$expr\$", expr.text)
    val caret = filled.indexOf("\$END\$").takeIf { it >= 0 } ?: filled.length
    val text = filled.replace("\$END\$", "")
    val start = target.textRange.startOffset
    editor.document.replaceString(start, target.textRange.endOffset, text)
    editor.caretModel.moveToOffset(start + caret)
    PsiDocumentManager.getInstance(expr.project).commitDocument(editor.document)
  }
}

class SolPostfixTemplateProvider : PostfixTemplateProvider {
  private val all: Set<PostfixTemplate> by lazy {
    setOf(
      SolTemplate("if", "if (expr) {}", this, statement = true) { e ->
        val indent = indentOf(e)
        "if (\$expr\$) {\n$indent    \$END\$\n$indent}"
      },
      SolTemplate("require", "require(expr, \"\");", this, statement = true) { "require(\$expr\$, \"\$END\$\");" },
      SolTemplate("return", "return expr;", this, statement = true) { "return \$expr\$;\$END\$" },
      SolTemplate("not", "!expr", this, statement = false) { e -> if (needsParens(e)) "!(\$expr\$)\$END\$" else "!\$expr\$\$END\$" },
      SolTemplate("par", "(expr)", this, statement = false) { "(\$expr\$)\$END\$" },
    )
  }

  override fun getId() = "solidity.postfix"
  override fun getPresentableName() = "Solidity"
  override fun getTemplates(): Set<PostfixTemplate> = all
  override fun isTerminalSymbol(currentChar: Char) = currentChar == '.' || currentChar == '!'
  // An expression being typed on its own line has no `;` yet, and without it the line doesn't parse as a statement:
  // add one when nothing follows on the line.
  override fun preExpand(file: PsiFile, editor: Editor) {
    val document = editor.document
    val offset = editor.caretModel.offset
    if (needsSemicolon(document.charsSequence, offset)) {
      document.insertString(offset, ";")
      PsiDocumentManager.getInstance(file.project).commitDocument(document)
    }
  }

  override fun afterExpand(file: PsiFile, editor: Editor) {}

  override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile {
    val document = copyFile.viewProvider.document ?: return copyFile
    if (needsSemicolon(document.charsSequence, currentOffset)) {
      document.insertString(currentOffset, ";")
      PsiDocumentManager.getInstance(copyFile.project).commitDocument(document)
    }
    return copyFile
  }

  private fun needsSemicolon(text: CharSequence, offset: Int): Boolean {
    var i = offset
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return i >= text.length || text[i] == '\n' || text[i] == '\r'
  }
}
