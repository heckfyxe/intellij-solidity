package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.AddDataLocationFix
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver

/**
 * Mirrors solc's "Data location must be ... but none was given" error for parameters, return parameters and local
 * variables of reference types in functions with a body. Types that don't resolve are skipped.
 */
class MissingDataLocationInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

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
        o.parameters.forEach { check(it, it.typeName, storageAllowed, external, context) }
        o.returns?.parameterDefList?.forEach {
          check(it, it.typeName, storageAllowed, false, "return parameter in function")
        }
      }

      override fun visitConstructorDefinition(o: SolConstructorDefinition) {
        if (isLegacy(o)) {
          return
        }
        o.parameterList?.parameterDefList?.forEach {
          check(it, it.typeName, false, false, "parameter in function")
        }
      }

      override fun visitVariableDeclaration(o: SolVariableDeclaration) {
        if (o.identifier == null || !isLocal(o) || isLegacy(o)) {
          return
        }
        check(o, o.typeName, true, false, "variable")
      }

      override fun visitTypedDeclarationItem(o: SolTypedDeclarationItem) {
        if (o.identifier == null || !isLocal(o) || isLegacy(o)) {
          return
        }
        check(o, o.typeName, true, false, "variable")
      }

      private fun check(
        declaration: PsiElement,
        typeName: SolTypeName?,
        storageAllowed: Boolean,
        calldataPreferred: Boolean,
        context: String
      ) {
        typeName ?: return
        if (dataLocation(declaration, typeName) != null) {
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
    return when (typeName) {
      is SolArrayTypeName -> ReferenceKind.ARRAY
      is SolBytesArrayTypeName -> if (typeName.bytesNumType.text == "bytes") ReferenceKind.ARRAY else null
      is SolElementaryTypeName -> when (typeName.text) {
        "string", "bytes" -> ReferenceKind.ARRAY
        else -> null
      }
      is SolMappingTypeName -> ReferenceKind.MAPPING
      is SolUserDefinedLocationTypeName, is SolUserDefinedTypeName -> {
        val userType = (typeName as? SolUserDefinedLocationTypeName)?.userDefinedTypeName ?: typeName as? SolUserDefinedTypeName ?: return null
        val resolved = SolResolver.resolveTypeNameUsingImports(userType)
        if (resolved.isNotEmpty() && resolved.all { it is SolStructDefinition }) ReferenceKind.STRUCT else null
      }
      else -> null
    }
  }

  private fun isLocal(declaration: PsiElement): Boolean {
    val block = declaration.parentOfType<SolBlock>() ?: return false
    var current: PsiElement? = block
    while (current != null) {
      when (current) {
        is SolFunctionDefinition, is SolConstructorDefinition, is SolModifierDefinition -> return true
        is SolContractDefinition, is SolidityFile -> return false
      }
      current = current.parent
    }
    return false
  }

  private fun isLegacy(element: PsiElement) = element.containingFile.allowsSolidityOlderThan(5)
}
