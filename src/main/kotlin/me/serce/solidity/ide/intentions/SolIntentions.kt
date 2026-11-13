package me.serce.solidity.ide.intentions

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.ide.hints.comments
import me.serce.solidity.lang.psi.*

private const val FAMILY = "Solidity"

/** The indentation of the line [offset] is on. */
private fun indentAt(text: CharSequence, offset: Int): String {
  var start = offset
  while (start > 0 && text[start - 1] != '\n') start--
  var end = start
  while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
  return text.subSequence(start, end).toString()
}

/** One level of indentation, as used in the file (a tab, or the smallest space indent, 4 by default). */
private fun indentUnit(text: CharSequence): String {
  val indents = text.lines().map { line -> line.takeWhile { it == ' ' || it == '\t' } }.filter { it.isNotEmpty() && it.isNotBlank() }
  if (indents.any { it.startsWith("\t") }) return "\t"
  val smallest = indents.minOfOrNull { it.length } ?: 4
  return " ".repeat(smallest.coerceIn(2, 8))
}

/**
 * Adds a NatSpec comment above a contract, function, modifier, event or error: `@notice`, one `@param` per named
 * parameter and one `@return` per return value.
 */
class AddNatSpecIntention : PsiElementBaseIntentionAction() {
  override fun getFamilyName() = FAMILY
  override fun getText() = "Add NatSpec documentation"

  override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
    val owner = owner(element) ?: return false
    return !hasDocComment(owner)
  }

  /** The definition whose header (not body) holds [element]. */
  private fun owner(element: PsiElement): PsiElement? {
    val owner = PsiTreeUtil.getParentOfType(
      element, false,
      SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolEventDefinition::class.java,
      SolErrorDefinition::class.java, SolContractDefinition::class.java,
    ) ?: return null
    val body = when (owner) {
      is SolFunctionDefinition -> owner.block
      is SolModifierDefinition -> owner.block
      is SolContractDefinition -> owner.node.findChildByType(SolidityTokenTypes.LBRACE)?.psi
      else -> null
    }
    if (body != null && element.textRange.startOffset >= body.textRange.startOffset) return null
    return owner
  }

  /** NatSpec (`///` lines or a `/** */` block) right before [owner]. */
  private fun hasDocComment(owner: PsiElement): Boolean = owner.comments().isNotEmpty()

  override fun invoke(project: Project, editor: Editor, element: PsiElement) {
    val owner = owner(element) ?: return
    val document = editor.document
    val text = document.charsSequence
    val start = owner.textRange.startOffset
    val indent = indentAt(text, start)
    val lines = mutableListOf("/// @notice ")
    if (owner is SolContractDefinition) lines.add(0, "/// @title ${owner.name ?: ""}")
    paramNames(owner).forEach { lines += "/// @param $it " }
    returnNames(owner).forEach { lines += "/// @return ${it.orEmpty()}".trimEnd() + " " }
    val comment = lines.joinToString("") { "$it\n$indent" }
    document.insertString(start, comment)
    PsiDocumentManager.getInstance(project).commitDocument(document)
    val notice = comment.indexOf("@notice ") + "@notice ".length
    editor.caretModel.moveToOffset(start + notice)
  }

  private fun paramNames(owner: PsiElement): List<String> = when (owner) {
    is SolFunctionDefElement -> owner.parameters.mapNotNull { it.identifier?.text }
    is SolModifierDefinition -> owner.parameterList?.parameterDefList?.mapNotNull { it.identifier?.text }.orEmpty()
    is SolEventDefinition -> owner.indexedParameterList?.indexedParamDefList?.mapNotNull { it.identifier?.text }.orEmpty()
    is SolErrorDefinition -> owner.indexedParameterList?.indexedParamDefList?.mapNotNull { it.identifier?.text }.orEmpty()
    else -> emptyList()
  }

  private fun returnNames(owner: PsiElement): List<String?> =
    (owner as? SolFunctionDefElement)?.returns?.parameterDefList?.map { it.identifier?.text }.orEmpty()
}

/** `1000000` to `1_000_000` and back, for decimal literals. */
class DigitSeparatorsIntention : PsiElementBaseIntentionAction() {
  private var add = true

  override fun getFamilyName() = FAMILY
  override fun getText() = if (add) "Add digit separators" else "Remove digit separators"

  private fun literal(element: PsiElement): PsiElement? {
    val token = if (element.elementType == SolidityTokenTypes.DECIMALNUMBER) element
      else (element.prevSibling?.takeIf { it.elementType == SolidityTokenTypes.DECIMALNUMBER })
    return token
  }

  override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
    val token = literal(element) ?: literal(PsiTreeUtil.prevLeaf(element) ?: return false) ?: return false
    val text = token.text
    add = '_' !in text
    return !add || text.length > 4
  }

  override fun invoke(project: Project, editor: Editor, element: PsiElement) {
    val token = literal(element) ?: literal(PsiTreeUtil.prevLeaf(element) ?: return) ?: return
    val digits = token.text.replace("_", "")
    val replacement = if ('_' in token.text) digits else digits.reversed().chunked(3).joinToString("_").reversed()
    editor.document.replaceString(token.textRange.startOffset, token.textRange.endOffset, replacement)
  }
}

/** Wraps the statement at the caret in `unchecked { ... }`. */
class WrapInUncheckedIntention : PsiElementBaseIntentionAction() {
  override fun getFamilyName() = FAMILY
  override fun getText() = "Wrap in 'unchecked' block"

  private fun statement(element: PsiElement): SolStatement? {
    var st = PsiTreeUtil.getParentOfType(element, SolStatement::class.java, false) ?: return null
    // the outermost statement directly inside a block
    while (st.parent !is SolBlock) st = PsiTreeUtil.getParentOfType(st, SolStatement::class.java, true) ?: return null
    return st
  }

  override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
    val st = statement(element) ?: return false
    if (PsiTreeUtil.getParentOfType(st, SolUncheckedBlock::class.java) != null) return false
    if (st.block != null) return false // a nested `{ }` block
    return PsiTreeUtil.getParentOfType(st, SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolConstructorDefinition::class.java) != null
  }

  override fun invoke(project: Project, editor: Editor, element: PsiElement) {
    val st = statement(element) ?: return
    val document = editor.document
    val text = document.charsSequence
    val indent = indentAt(text, st.textRange.startOffset)
    val unit = indentUnit(text)
    val body = st.text.lines().joinToString("\n") { line -> if (line.isBlank()) line else "$unit$line" }
    val replacement = "unchecked {\n$indent$body\n$indent}"
    val start = st.textRange.startOffset
    document.replaceString(start, st.textRange.endOffset, replacement)
    editor.caretModel.moveToOffset(start + "unchecked {\n$indent$unit".length)
  }
}
