package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.RenameFix
import me.serce.solidity.lang.core.isSolidityVersionAtLeast
import me.serce.solidity.lang.psi.SolFunctionCallExpression
import me.serce.solidity.lang.psi.SolVisitor

class SelfdestructRenameInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionCallExpression(o: SolFunctionCallExpression) {
        inspectCall(o, holder)
      }
    }
  }

  private fun inspectCall(expr: SolFunctionCallExpression, holder: ProblemsHolder) {
    // reported as an error by RemovedLanguageFeatureInspection when the pragma guarantees 0.5.0+
    if (expr.isSolidityVersionAtLeast(SolVersions.V0_5_0)) return
    val name = expr.referenceName
    if (name == "suicide") {
      holder.registerProblem(expr, "Suicide is deprecated. rename to selfdestruct. EIP 6",
        RenameFix(expr, "selfdestruct"))
    }
  }

  override fun getID(): String = "suicide_deprecated"
}
