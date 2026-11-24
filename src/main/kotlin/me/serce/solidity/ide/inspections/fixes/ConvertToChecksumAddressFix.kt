package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class ConvertToChecksumAddressFix(private val checksummed: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Convert to checksummed address"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val element = descriptor.psiElement
    val range = element.textRange
    editDocument(element) { it.replaceString(range.startOffset, range.endOffset, checksummed) }
  }
}