package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.ide.inspections.fixes.AddDataLocationFix
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.*

/**
 * Mirrors solc's "Data location must be ... but none was given" error for parameters, return parameters and local
 * variables of reference types in functions with a body. Types that don't resolve are skipped.
 */
class MissingDataLocationInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        if (o.block == null || isLegacy(o)) {
          return
        }
        val contract = o.contract
        val visibility = o.visibility ?: if (contract == null) Visibility.INTERNAL else null
        val storageAllowed = visibility == Visibility.INTERNAL || visibility == Visibility.PRIVATE ||
          contract?.contractType == ContractType.LIBRARY
        val external = visibility == Visibility.EXTERNAL
        val context = if (external) "parameter in external function" else "parameter in function"
        o.parameters.forEach { check(it, storageAllowed, external, context) }
        o.returns?.parameterDefList?.forEach {
          check(it, storageAllowed, false, "return parameter in function")
        }
      }

      override fun visitConstructorDefinition(o: SolConstructorDefinition) {
        if (isLegacy(o)) {
          return
        }
        o.parameterList?.parameterDefList?.forEach {
          check(it, false, false, "parameter in function")
        }
      }

      override fun visitVariableDeclaration(o: SolVariableDeclaration) = checkLocal(o, o.identifier)

      override fun visitTypedDeclarationItem(o: SolTypedDeclarationItem) = checkLocal(o, o.identifier)

      private fun checkLocal(declaration: SolTypedDeclaration, identifier: PsiElement?) {
        if (identifier == null || !isLocal(declaration) || isLegacy(declaration)) {
          return
        }
        check(declaration, true, false, "variable")
      }

      private fun check(
        declaration: SolTypedDeclaration,
        storageAllowed: Boolean,
        calldataPreferred: Boolean,
        context: String
      ) {
        val typeName = declaration.typeName ?: return
        if (declaration.dataLocation != null) {
          return
        }
        val kind = referenceKind(typeName) ?: return
        if (kind == ReferenceKind.MAPPING) {
          if (storageAllowed) {
            holder.registerProblem(
              declaration,
              "Data location must be \"storage\" for $context, but none was given",
              ProblemHighlightType.GENERIC_ERROR,
              highlightRange(declaration, typeName),
              AddDataLocationFix("storage")
            )
          }
          return
        }
        val locations = if (storageAllowed) "\"memory\", \"storage\" or \"calldata\"" else "\"memory\" or \"calldata\""
        val fixes = if (calldataPreferred) {
          arrayOf(AddDataLocationFix("calldata"), AddDataLocationFix("memory"))
        } else {
          arrayOf(AddDataLocationFix("memory"))
        }
        holder.registerProblem(
          declaration,
          "Data location must be $locations for $context, but none was given",
          ProblemHighlightType.GENERIC_ERROR,
          highlightRange(declaration, typeName),
          *fixes
        )
      }
    }
  }

  /** From the type to the name, without the trailing comma of tuple items. */
  private fun highlightRange(declaration: PsiElement, typeName: SolTypeName): TextRange {
    val end = declaration.node.findChildByType(SolidityTokenTypes.IDENTIFIER)?.psi ?: typeName
    return TextRange(0, end.textRange.endOffset - declaration.textRange.startOffset)
  }

  private enum class ReferenceKind { ARRAY, STRUCT, MAPPING }

  private fun referenceKind(typeName: SolTypeName): ReferenceKind? {
    // the type loses array sizes that aren't literals (`uint[N]`)
    if (typeName is SolArrayTypeName) return ReferenceKind.ARRAY
    return when (getSolType(typeName)) {
      is SolString, is SolArray -> ReferenceKind.ARRAY
      is SolStruct -> ReferenceKind.STRUCT
      is SolMapping -> ReferenceKind.MAPPING
      else -> null
    }
  }

  private fun isLocal(declaration: PsiElement): Boolean {
    val block = declaration.parentOfType<SolBlock>() ?: return false
    return PsiTreeUtil.getParentOfType(block, SolFunctionDefinition::class.java, SolConstructorDefinition::class.java,
      SolModifierDefinition::class.java) != null
  }

  private fun isLegacy(element: PsiElement) = element.containingFile.allowsSolidityOlderThan(SolVersions.V0_5_0)
}
