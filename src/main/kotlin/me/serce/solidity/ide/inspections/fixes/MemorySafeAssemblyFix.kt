package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.hints.comments
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.SolAssemblyFlags
import me.serce.solidity.lang.psi.SolInlineAssemblyStatement
import me.serce.solidity.lang.psi.SolStatement
import me.serce.solidity.lang.psi.SolStringLiteral

/**
 * Replaces the deprecated `/// @solidity memory-safe-assembly` comment of an assembly block with the
 * `assembly ("memory-safe")` flag.
 */
class MemorySafeAssemblyFix(assembly: SolInlineAssemblyStatement) : LocalQuickFixOnPsiElement(assembly) {
  override fun getText() = "Replace with 'assembly (\"memory-safe\")'"
  override fun getFamilyName() = text

  override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
    val assembly = startElement as? SolInlineAssemblyStatement ?: return
    val documentManager = PsiDocumentManager.getInstance(project)
    val document = documentManager.getDocument(file) ?: return
    val commentRange = commentRange(assembly, document) ?: return

    val flags = assembly.children.filterIsInstance<SolAssemblyFlags>().firstOrNull()
    when {
      flags == null -> {
        val anchor = assembly.children.filterIsInstance<SolStringLiteral>().firstOrNull() ?: assembly.firstChild
        document.insertString(anchor.textRange.endOffset, " (\"memory-safe\")")
      }
      !flags.text.contains("\"memory-safe\"") -> {
        val closing = flags.lastChild.takeIf { it.text == ")" } ?: return
        document.insertString(closing.textRange.startOffset, ", \"memory-safe\"")
      }
    }
    document.deleteString(commentRange.startOffset, commentRange.endOffset)
    documentManager.commitDocument(document)
  }

  /**
   * The text to delete: the whole line of a `/// @solidity memory-safe-assembly` comment (or a block comment holding
   * only that tag), otherwise just the tag and its argument.
   */
  private fun commentRange(assembly: SolInlineAssemblyStatement, document: Document): TextRange? {
    val comments = (assembly.parent as? SolStatement ?: assembly).comments().sortedBy { it.textRange.startOffset }
    val tagIndex = comments.indices.firstOrNull { i ->
      comments[i].elementType == SolidityTokenTypes.NAT_SPEC_TAG && comments[i].text == "@solidity"
    } ?: return null
    val tag = comments[tagIndex]
    val argument = comments.getOrNull(tagIndex + 1) ?: return null
    val line = document.getLineNumber(tag.textRange.startOffset)
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val lineText = document.getText(TextRange(lineStart, lineEnd)).trim()
    val wholeLine = TextRange(lineStart, minOf(lineEnd + 1, document.textLength))
    val onlyTag = Regex("""(///|/\*\*)\s*@solidity\s+memory-safe-assembly\s*(\*/)?""")
    if (onlyTag.matches(lineText) && (lineText.startsWith("///") || lineText.endsWith("*/"))) {
      return wholeLine
    }
    val argumentText = argument.text
    val keyword = argumentText.indexOf("memory-safe-assembly")
    if (keyword < 0) return null
    return TextRange(tag.textRange.startOffset, argument.textRange.startOffset + keyword + "memory-safe-assembly".length)
  }
}
