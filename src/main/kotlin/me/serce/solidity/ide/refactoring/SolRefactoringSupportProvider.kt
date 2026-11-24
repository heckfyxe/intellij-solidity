package me.serce.solidity.ide.refactoring

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement

class SolRefactoringSupportProvider : RefactoringSupportProvider() {
  override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = true

  override fun getIntroduceVariableHandler() = SolIntroduceVariableHandler()

  override fun getExtractMethodHandler() = SolExtractFunctionHandler()
}
