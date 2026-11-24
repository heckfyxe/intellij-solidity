package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class PrependZeroAddressFix : LocalQuickFix {
  override fun getFamilyName(): String = "Prepend '00' to treat as a number"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val element = descriptor.psiElement
    val replacement = "0x00" + element.text.removePrefix("0x")
    val range = element.textRange
    editDocument(element) { it.replaceString(range.startOffset, range.endOffset, replacement) }
  }
}