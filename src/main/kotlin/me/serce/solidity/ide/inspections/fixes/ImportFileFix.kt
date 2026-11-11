package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInspection.HintAction
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.serce.solidity.ide.inspections.fixes.ImportFileAction.Companion.buildImportPath
import me.serce.solidity.lang.psi.SolReferenceElement
import me.serce.solidity.lang.resolve.SolResolver

class ImportFileFix(element: SolReferenceElement) : LocalQuickFixOnPsiElement(element), HintAction, LocalQuickFix {
  override fun startInWriteAction(): Boolean = false

  override fun getFamilyName(): String = "Import file"

  override fun showHint(editor: Editor): Boolean {
    val element = startElement as? SolReferenceElement ?: return false
    val suggestions = resolveSuggestions(element)
    val fixText = when {
      suggestions.size == 1 -> {
        val importPath = buildImportPath(element.project, element.containingFile.virtualFile, suggestions.first().virtualFile)
        "$familyName $importPath"
      }

      suggestions.isNotEmpty() -> familyName
      else -> return false
    }
    HintManager.getInstance().showQuestionHint(editor, fixText, element.textOffset, element.textRange.endOffset,
      ImportFileAction(editor, element.containingFile, suggestions)
    )
    return true
  }

  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
    val element = startElement as? SolReferenceElement ?: return false
    return element.isValid && element.reference?.resolve() == null && SolResolver.resolveTypeName(element).isNotEmpty()
  }

  override fun getText(): String = familyName

  override fun invoke(project: Project, file: PsiFile, element: PsiElement, endElement: PsiElement) {
    applyImportFix(element as? SolReferenceElement ?: return, null)
  }

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    applyImportFix(startElement as? SolReferenceElement ?: return, editor)
  }

  private fun resolveSuggestions(element: SolReferenceElement): Set<PsiFile> {
    return SolResolver.resolveTypeName(element).map { it.containingFile }.toSet()
  }

  private fun applyImportFix(element: SolReferenceElement, editor: Editor?) {
    val suggestions = resolveSuggestions(element)
    when {
      suggestions.size == 1 -> ImportFileAction.addImport(element.project, element.containingFile, suggestions.first())
      suggestions.size > 1 && editor != null -> ImportFileAction(editor, element.containingFile, suggestions).execute()
    }
  }
}
