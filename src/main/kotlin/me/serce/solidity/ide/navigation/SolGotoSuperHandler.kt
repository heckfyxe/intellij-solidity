package me.serce.solidity.ide.navigation

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolModifierDefinition
import me.serce.solidity.lang.psi.SolStateVariableDeclaration
import me.serce.solidity.lang.resolve.function.SolOverrideResolver
import me.serce.solidity.lang.resolve.function.SolFunctionResolver
import me.serce.solidity.lang.types.SolContract

/**
 * Go to Super (Ctrl/Cmd+U): the functions and modifiers the one at the caret overrides, the functions a public state
 * variable overrides, or the base contracts of the contract at the caret.
 */
class SolGotoSuperHandler : LanguageCodeInsightActionHandler {
  override fun isValidFor(editor: Editor, file: PsiFile) = file is SolidityFile

  override fun startInWriteAction() = false

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val element = file.findElementAt(editor.caretModel.offset) ?: return
    val targets = superTargets(element)
    if (targets.isEmpty()) return
    PsiTargetNavigator(targets).navigate(editor, "Choose Super Definition")
  }

  companion object {
    fun superTargets(at: PsiElement): List<PsiElement> {
      val owner = PsiTreeUtil.getParentOfType(
        at, false,
        SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolStateVariableDeclaration::class.java,
        SolContractDefinition::class.java,
      ) ?: return emptyList()
      return when (owner) {
        is SolFunctionDefinition -> SolFunctionResolver.collectOverridden(owner).toList()
        is SolModifierDefinition -> overriddenModifiers(owner)
        is SolStateVariableDeclaration -> overriddenByStateVariable(owner)
        is SolContractDefinition -> SolContract(owner).getParents().map { it.ref }.asReversed()
        else -> emptyList()
      }
    }

    /** The nearest definitions of the same modifier in the base contracts, in linearization order. */
    private fun overriddenModifiers(modifier: SolModifierDefinition): List<PsiElement> {
      val contract = PsiTreeUtil.getParentOfType(modifier, SolContractDefinition::class.java) ?: return emptyList()
      val name = modifier.name ?: return emptyList()
      return (SolOverrideResolver.linearizedParents(contract) ?: emptyList())
        .flatMap { it.modifierDefinitionList }
        .filter { it.name == name }
    }

    /** A public state variable overrides external functions without parameters of the same name. */
    private fun overriddenByStateVariable(variable: SolStateVariableDeclaration): List<PsiElement> {
      val contract = PsiTreeUtil.getParentOfType(variable, SolContractDefinition::class.java) ?: return emptyList()
      val name = variable.name ?: return emptyList()
      return (SolOverrideResolver.linearizedParents(contract) ?: emptyList())
        .flatMap { it.functionDefinitionList }
        .filter { it.name == name && it.parameters.isEmpty() }
    }
  }
}
