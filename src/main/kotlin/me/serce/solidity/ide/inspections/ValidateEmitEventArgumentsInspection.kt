package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.hints.hasNoValidationTag
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver

class ValidateEmitEventArgumentsInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitEmitStatement(o: SolEmitStatement) {
        val call = (o.parent?.children?.firstOrNull() as? SolEmitStatement)?.lastChild as? SolFunctionCallExpression
          ?: return
        val args = call.functionCallArguments.expressionList
        val defs = SolResolver.resolveVarLiteralReference(call).filterIsInstance<SolEventDefinition>()
        if (defs.isEmpty()) {
          holder.registerProblem(o.parent?.firstChild?.lastChild?.firstChild ?: o, "Can't find the event in that contract")
          return
        }

        val events = defs.filterNot { it.hasNoValidationTag() }
        if (events.isEmpty()) return
        if (args.isEmpty()) {
          // `emit Ping();` is fine for `event Ping();`
          if (events.none { it.parseParameters().isEmpty() }) holder.registerProblem(o, "No arguments")
          return
        }
        val matcher = ArgumentMatcher(args)
        if (events.none { event ->
            val params = event.parseParameters()
            matcher.matches(params.takeIf { it.size == args.size }, params.size) { it.second }
          }) {
          holder.registerProblem(o, matcher.message)
        }
      }
    }
  }
}
