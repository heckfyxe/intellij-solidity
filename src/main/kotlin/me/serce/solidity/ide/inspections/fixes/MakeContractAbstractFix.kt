package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import me.serce.solidity.lang.psi.findContract

class MakeContractAbstractFix : LocalQuickFix {
  override fun getFamilyName(): String = "Make contract abstract"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val element = descriptor.psiElement
    val contract = element.findContract() ?: return
    if (contract.isAbstract) {
      return
    }
    val offset = contract.firstChild.textRange.startOffset
    editDocument(contract) { it.insertString(offset, "abstract ") }
  }
}
