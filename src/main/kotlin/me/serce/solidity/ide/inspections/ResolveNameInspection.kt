package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.ImportFileFix
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.YulBuiltins

class ResolveNameInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitImportDirective(o: SolImportDirective) {
        o.importPath?.let { path ->
          if (path.reference?.resolve() == null) {
            holder.registerProblem(path, "'${path.text}' cannot be resolved", ProblemHighlightType.WARNING)
          } else {
            o.importAliasedPairList.filter { it.userDefinedTypeName.reference?.resolve() == null }.forEach {
              holder.registerProblem(it.userDefinedTypeName, "'${it.userDefinedTypeName.text}' cannot be resolved", ProblemHighlightType.WARNING)
            }
          }
        }
      }

      override fun visitVarLiteral(element: SolVarLiteral) {
        checkReference(element) {
          holder.registerProblem(element, "'${element.identifier.text}' is undefined", ProblemHighlightType.WARNING, ImportFileFix(element))
        }
      }

      override fun visitYulPath(element: SolYulPath) {
        checkReference(element) {
          holder.registerProblem(element, "'${element.referenceName}' is undefined", ProblemHighlightType.WARNING, element.reference!!.rangeInElement)
        }
      }

      override fun visitYulFunctionCall(element: SolYulFunctionCall) {
        val call = element as? SolReferenceElement ?: return
        if (YulBuiltins.isBuiltin(call.referenceName)) return
        checkReference(call) {
          holder.registerProblem(element, "Function '${call.referenceName}' is undefined", ProblemHighlightType.WARNING, call.reference!!.rangeInElement)
        }
      }

      override fun visitUserDefinedTypeName(element: SolUserDefinedTypeName) {
        checkReference(element) {
          holder.registerProblem(element, "Import file", ProblemHighlightType.WARNING, ImportFileFix(element))
        }
      }
    }
  }
}

private fun checkReference(element: SolReferenceElement, report: () -> Unit) {
  val reference = element.reference ?: return
  // resolve returns either 1 reference or null, and because our resolve is not perfect we can return a number
  // of references, so instead of showing false positives we use multiResolve
  if (reference.multiResolve(false).isEmpty()) {
    report()
  }
}

