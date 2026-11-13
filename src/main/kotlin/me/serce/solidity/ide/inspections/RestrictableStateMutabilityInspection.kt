package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.AddStateMutabilityFix
import me.serce.solidity.lang.psi.ContractType
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolVisitor
import me.serce.solidity.lang.resolve.function.SolFunctionResolver

/**
 * Mirrors solc's "Function state mutability can be restricted to view/pure" warning. Only functions that can be
 * analysed completely are reported: virtual, overriding, payable and special functions as well as functions with
 * modifiers, inline assembly or unresolved references are skipped.
 */
class RestrictableStateMutabilityInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        val identifier = o.identifier ?: return
        val body = o.block ?: return
        if (o.mutability != null || o.specialFunction != null || o.isConstructor) {
          return
        }
        if (o.contract?.contractType == ContractType.INTERFACE || o.modifiers.isNotEmpty()) {
          return
        }
        if (o.functionVisibilitySpecifierList.any { it.virtualSpecifier != null || it.overrideSpecifier != null }) {
          return
        }
        if (o.containingFile.allowsSolidityOlderThan(5)) {
          return
        }
        if (SolFunctionResolver.collectOverridden(o).isNotEmpty()) {
          return
        }
        val analysis = StateMutabilityAnalyzer.analyze(body)
        if (analysis.incomplete || analysis.requiresPayable || analysis.modifiesState) {
          return
        }
        val target = if (analysis.effects.isEmpty() && !analysis.mayRead) "pure" else "view"
        holder.registerProblem(
          identifier,
          "Function state mutability can be restricted to $target",
          ProblemHighlightType.WEAK_WARNING,
          AddStateMutabilityFix(target)
        )
      }
    }
  }
}
