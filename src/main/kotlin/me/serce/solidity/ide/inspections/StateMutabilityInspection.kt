package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.ChangeStateMutabilityFix
import me.serce.solidity.ide.inspections.fixes.RemoveStateMutabilityFix
import me.serce.solidity.lang.psi.Mutability
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolVisitor

/**
 * Mirrors solc's errors for `view` functions that modify the state and `pure` functions that read or modify it.
 */
class StateMutabilityInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        val mutability = o.mutability
        if (mutability != Mutability.VIEW && mutability != Mutability.PURE) {
          return
        }
        val body = o.block ?: return
        if (o.specialFunction != null || o.containingFile.allowsSolidityOlderThan(5)) {
          return
        }
        val analysis = StateMutabilityAnalyzer.analyze(body)
        val keyword = mutability.name.lowercase()
        for (effect in analysis.effects) {
          val (message, fixes) = when {
            effect.level == MutabilityLevel.NONPAYABLE -> Pair(
              "Function declared as $keyword, but this expression (potentially) modifies the state and thus requires non-payable (the default) or payable",
              arrayOf<LocalQuickFix>(RemoveStateMutabilityFix(keyword))
            )
            effect.level == MutabilityLevel.VIEW && mutability == Mutability.PURE -> Pair(
              "Function declared as pure, but this expression (potentially) reads from the environment or state and thus requires \"view\"",
              if (analysis.modifiesState) {
                arrayOf<LocalQuickFix>(RemoveStateMutabilityFix(keyword))
              } else {
                arrayOf(ChangeStateMutabilityFix(keyword, "view"), RemoveStateMutabilityFix(keyword))
              }
            )
            else -> continue
          }
          holder.registerProblem(effect.element, message, ProblemHighlightType.GENERIC_ERROR, *fixes)
        }
      }
    }
  }
}
