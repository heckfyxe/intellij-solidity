package me.serce.solidity.ide

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.DocumentUtil

// The plugin has no formatter of its own, so code that inserts text indents it with these helpers.

/** The spaces and tabs at the start of the line [offset] is on. */
fun indentAt(document: Document, offset: Int): String = DocumentUtil.getIndent(document, offset).toString()

/** The indentation of the line [element] starts on. */
fun lineIndentOf(element: PsiElement): String {
  val document = element.containingFile.viewProvider.document ?: return ""
  return indentAt(document, element.textRange.startOffset)
}

/**
 * One level of indentation in [file]: what the file already uses (a tab, or its smallest space indent), since
 * forge fmt may be configured differently from the IDE's code style; the code style's when nothing is indented yet.
 */
fun indentUnit(file: PsiFile): String {
  val text = file.viewProvider.document?.charsSequence ?: file.text
  val indents = text.lines()
    .map { line -> line.takeWhile { it == ' ' || it == '\t' } to line.trimStart() }
    // ` * ...` continues a block comment, one space in
    .filter { (indent, rest) -> indent.isNotEmpty() && rest.isNotEmpty() && !rest.startsWith("*") }
    .map { it.first }
  if (indents.any { it.startsWith("\t") }) return "\t"
  indents.minOfOrNull { it.length }?.let { return " ".repeat(it.coerceIn(2, 8)) }
  val options = CodeStyle.getIndentOptions(file)
  return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
}
