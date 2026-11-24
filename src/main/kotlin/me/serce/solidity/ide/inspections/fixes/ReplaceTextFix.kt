package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Replaces the text of [element] with [replacement]. */
class ReplaceTextFix(
  element: PsiElement,
  private val replacement: String,
  private val fixName: String = "Replace with '$replacement'"
) : LocalQuickFixOnPsiElement(element) {
  override fun getText() = fixName
  override fun getFamilyName() = "Replace deprecated or removed construct"

  override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
    val range = startElement.textRange
    editDocument(startElement) { it.replaceString(range.startOffset, range.endOffset, replacement) }
  }
}
