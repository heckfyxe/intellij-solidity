package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.*

/**
 * Reports the misuses of `using ... for` that solc rejects:
 * `global` outside file level, `global` with a type that isn't a user-defined type defined at file level of the same
 * file, and `*` at file level.
 */
class UsingForGlobalInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitUsingForDeclaration(o: SolUsingForDeclaration) {
        val atFileLevel = o.parent is PsiFile
        val globalKeyword = o.node.getChildren(null).firstOrNull { it.psi !is SolTypeName && it.text == "global" }?.psi
        val isWildcard = o.type == null
        if (atFileLevel && isWildcard) {
          val star = o.node.getChildren(null).firstOrNull { it.text == "*" }?.psi ?: o
          report(star, "The type has to be specified explicitly at file level (cannot use \"*\")")
          return
        }
        if (globalKeyword == null) return
        if (!atFileLevel) {
          report(globalKeyword, "\"global\" can only be used at file level")
          return
        }
        val typeName = o.getTypeNameList().lastOrNull() ?: return
        val definition: PsiElement? = when (val type = getSolType(typeName)) {
          SolUnknown -> return
          is SolStruct -> type.ref
          is SolEnum -> type.ref
          is SolUserDefinedValueTypeType -> type.ref
          is SolContract -> type.ref
          else -> null
        }
        if (definition == null) {
          report(typeName, "Can only use \"global\" with user-defined types")
        } else if (definition.parent != o.containingFile) {
          report(typeName, "Can only use \"global\" with types defined in the same source unit at file level")
        }
      }

      private fun report(element: PsiElement, message: String) {
        holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR)
      }
    }
  }
}
