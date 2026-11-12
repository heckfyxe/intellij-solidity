package me.serce.solidity.ide.typing

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import me.serce.solidity.lang.SolidityLanguage
import me.serce.solidity.lang.core.SolidityTokenTypes

/**
 * Continues NatSpec line comments: Enter in a `///` line starts the next line with `/// ` at the same indentation.
 * Plain `//` comments are not continued.
 */
class SolLineDocCommentEnterHandler : EnterHandlerDelegateAdapter() {
  override fun preprocessEnter(
    file: PsiFile,
    editor: Editor,
    caretOffset: Ref<Int>,
    caretAdvance: Ref<Int>,
    dataContext: DataContext,
    originalHandler: EditorActionHandler?
  ): Result {
    if (file.language != SolidityLanguage) return Result.Continue
    val document = editor.document
    val offset = caretOffset.get()
    val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
    val chars = document.charsSequence
    var commentStart = lineStart
    while (commentStart < offset && (chars[commentStart] == ' ' || chars[commentStart] == '\t')) commentStart++
    val prefix = chars.subSequence(commentStart, offset)
    if (!prefix.startsWith(DOC_PREFIX) || prefix.startsWith("////")) return Result.Continue

    // the `///` must start a line comment, not sit inside a block comment or a string
    PsiDocumentManager.getInstance(file.project).commitDocument(document)
    val element = file.findElementAt(commentStart) ?: return Result.Continue
    if (element.elementType != SolidityTokenTypes.COMMENT || element.textRange.startOffset != commentStart) {
      return Result.Continue
    }

    var afterCaret = offset
    while (afterCaret < chars.length && (chars[afterCaret] == ' ' || chars[afterCaret] == '\t')) afterCaret++
    document.deleteString(offset, afterCaret)
    val indent = chars.subSequence(lineStart, commentStart).toString()
    val insertion = "\n$indent$DOC_PREFIX "
    document.insertString(offset, insertion)
    editor.caretModel.moveToOffset(offset + insertion.length)
    return Result.Stop
  }

  private companion object {
    const val DOC_PREFIX = "///"
  }
}
