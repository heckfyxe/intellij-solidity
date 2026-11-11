package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.hints.hasNoValidationTag
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.types.SolInternalTypeFactory
import me.serce.solidity.lang.types.getSolType

class ValidateFunctionArgumentsInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionCallArguments(element: SolFunctionCallArguments) {
        val args = element.expressionList
        if (args.firstOrNull() is SolMapExpression) return
        val defs = resolveCallee(element) ?: return
        val funDefs = defs.filterNot { it.hasNoValidationTag() }
        if (funDefs.isEmpty()) return
        val matcher = ArgumentMatcher(args)
        if (funDefs.none { def ->
            val params = def.parameters
            val vararg = params.find { it.name == SolInternalTypeFactory.varargsId }
            val expected = when {
              vararg == null -> params.takeIf { it.size == args.size }
              args.size > params.size -> params + List(args.size - params.size) { vararg }
              else -> params
            }
            matcher.matches(expected, params.size) { getSolType(it.typeName) }
          }) {
          holder.registerProblem(element, matcher.message)
        }
      }
    }
  }

  private fun resolveCallee(arguments: SolFunctionCallArguments): List<SolFunctionDefElement>? {
    val call = arguments.parent?.parent
    val defs = (call?.children?.firstOrNull() as? SolMemberAccessExpression)?.let { SolResolver.resolveMemberAccess(it) }
      ?: (call as? SolFunctionCallExpression)?.let { SolResolver.resolveVarLiteralReference(it) }
    return defs?.filterIsInstance<SolFunctionDefElement>()
  }
}
