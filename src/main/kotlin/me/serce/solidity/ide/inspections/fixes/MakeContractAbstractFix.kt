package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.parentOfType

class MakeContractAbstractFix : LocalQuickFix {
  override fun getFamilyName(): String = "Make contract abstract"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val element = descriptor.psiElement
    val contract = element as? SolContractDefinition ?: element.parentOfType<SolContractDefinition>() ?: return
    if (contract.isAbstract) {
      return
    }
    val document = PsiDocumentManager.getInstance(project).getDocument(contract.containingFile) ?: return
    document.insertString(contract.firstChild.textRange.startOffset, "abstract ")
  }
}
