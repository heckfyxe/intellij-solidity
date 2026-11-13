package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolYulFunctionDefMixin

/**
 * Yul `let` variables and Yul function parameters whose value is never read. Assigning a variable with `:=` is not
 * a read; return variables are never reported, and names starting with `_` are taken as intentionally unused.
 */
class UnusedYulVariableInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : SolVisitor() {
    override fun visitYulVariable(o: SolYulVariable) {
      val name = o.name ?: return
      if (name.startsWith("_")) return
      val (scope, kind) = when (val parent = o.parent) {
        is SolYulVariableDeclaration -> letScope(parent) to "Variable"
        is SolYulFunctionDefinition -> {
          if (o !in (parent as SolYulFunctionDefMixin).parameters) return
          parent.yulBlock to "Parameter"
        }
        else -> return
      }
      if (scope == null || isRead(o, name, scope)) return
      val id = o.identifier
      holder.registerProblem(id, "$kind '$name' is never used", ProblemHighlightType.LIKE_UNUSED_SYMBOL)
    }
  }

  /** Where a `let` variable is visible: the rest of its block, or the whole `for` loop for its init block. */
  private fun letScope(declaration: SolYulVariableDeclaration): PsiElement? {
    val block = declaration.parent?.parent as? SolYulBlock ?: return null
    val loop = block.parent as? SolYulForStatement
    return if (loop != null && loop.yulBlockList.firstOrNull() == block) loop else block
  }

  private fun isRead(variable: SolYulVariable, name: String, scope: PsiElement): Boolean =
    PsiTreeUtil.findChildrenOfType(scope, SolYulPath::class.java).any { path ->
      path.parent !is SolYulAssignment && path.firstChild.text == name && path.reference?.resolve() == variable
    }
}
