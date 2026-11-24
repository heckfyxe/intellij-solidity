package me.serce.solidity.ide.inspections.fixes

import com.intellij.util.DocumentUtil
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.hints.comments
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.SolAssemblyFlags
import me.serce.solidity.lang.psi.SolInlineAssemblyStatement
import me.serce.solidity.lang.psi.SolStatement
import me.serce.solidity.lang.psi.SolStringLiteral

private fun SolInlineAssemblyStatement.docComments() = (parent as? SolStatement ?: this).comments()

/**
 * The deprecated `@solidity memory-safe-assembly` comment of [assembly]: its `@solidity` tag and the comment text after
 * it, or null when it has none.
 */
fun memorySafeAssemblyComment(assembly: SolInlineAssemblyStatement): Pair<PsiElement, PsiElement>? {
  val comments = assembly.docComments()
  val i = comments.indices.firstOrNull { i ->
    comments[i].elementType == SolidityTokenTypes.NAT_SPEC_TAG && comments[i].text == "@solidity" &&
      comments.getOrNull(i + 1)?.text?.trim()?.startsWith("memory-safe-assembly") == true
  } ?: return null
  return comments[i] to comments[i + 1]
}

/**
 * Replaces the deprecated `/// @solidity memory-safe-assembly` comment of an assembly block with the
 * `assembly ("memory-safe")` flag.
 */
class MemorySafeAssemblyFix(assembly: SolInlineAssemblyStatement) : LocalQuickFixOnPsiElement(assembly) {
  override fun getText() = "Replace with 'assembly (\"memory-safe\")'"
  override fun getFamilyName() = text

  override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
    val assembly = startElement as? SolInlineAssemblyStatement ?: return
    editDocument(assembly) { document ->
      val commentRange = commentRange(assembly, document) ?: return@editDocument
      val flags = assembly.children.filterIsInstance<SolAssemblyFlags>().firstOrNull()
      when {
        flags == null -> {
          val anchor = assembly.children.filterIsInstance<SolStringLiteral>().firstOrNull() ?: assembly.firstChild
          document.insertString(anchor.textRange.endOffset, " (\"memory-safe\")")
        }
        !flags.text.contains("\"memory-safe\"") -> {
          val closing = flags.lastChild.takeIf { it.text == ")" } ?: return@editDocument
          document.insertString(closing.textRange.startOffset, ", \"memory-safe\"")
        }
      }
      document.deleteString(commentRange.startOffset, commentRange.endOffset)
    }
  }

  /** The `/** ... */` comment [tag] is in, or null for `///` comments. */
  private fun blockComment(comments: List<PsiElement>, tag: PsiElement, document: Document): TextRange? {
    val text = document.charsSequence
    val open = text.lastIndexOf("/**", tag.textRange.startOffset).takeIf { it >= 0 } ?: return null
    val lastLineComment = text.lastIndexOf("///", tag.textRange.startOffset)
    if (lastLineComment > open) return null
    val close = text.indexOf("*/", tag.textRange.endOffset).takeIf { it >= 0 } ?: return null
    val first = comments.firstOrNull()?.textRange?.startOffset ?: return null
    if (open < first) return null
    return TextRange(open, close + 2)
  }

  private companion object {
    val TAG = Regex("""@solidity\s+memory-safe-assembly""")

    /** Lines [first] to [last] with the line break after them. */
    fun wholeLines(document: Document, first: Int, last: Int) =
      TextRange(document.getLineStartOffset(first), minOf(document.getLineEndOffset(last) + 1, document.textLength))
  }

  /**
   * The text to delete: the whole line of a `/// @solidity memory-safe-assembly` comment, a block comment holding
   * only that tag (on one line or several), or the tag's own line inside a longer block comment; otherwise just the
   * tag and its argument.
   */
  private fun commentRange(assembly: SolInlineAssemblyStatement, document: Document): TextRange? {
    val (tag, argument) = memorySafeAssemblyComment(assembly) ?: return null
    val line = document.getLineNumber(tag.textRange.startOffset)
    val lineText = document.getText(DocumentUtil.getLineTextRange(document, line)).trim()
    val wholeLine = wholeLines(document, line, line)
    val onlyTag = Regex("""(///|/\*\*)\s*@solidity\s+memory-safe-assembly\s*(\*/)?""")
    if (onlyTag.matches(lineText) && (lineText.startsWith("///") || lineText.endsWith("*/"))) {
      return wholeLine
    }
    blockComment(assembly.docComments(), tag, document)?.let { block ->
      val blockText = document.getText(block)
      val rest = blockText.replace(TAG, "").replace("/**", "").replace("*/", "").replace("*", "")
      // a block comment holding nothing else: delete it with its lines
      if (rest.isBlank()) return wholeLines(document, document.getLineNumber(block.startOffset), document.getLineNumber(block.endOffset))
      // ` * @solidity memory-safe-assembly` on its own line inside a longer block comment
      if (Regex("""\*?\s*@solidity\s+memory-safe-assembly""").matches(lineText)) return wholeLine
    }
    val argumentText = argument.text
    val keyword = argumentText.indexOf("memory-safe-assembly")
    if (keyword < 0) return null
    return TextRange(tag.textRange.startOffset, argument.textRange.startOffset + keyword + "memory-safe-assembly".length)
  }
}
