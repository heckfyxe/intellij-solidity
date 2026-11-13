package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import me.serce.solidity.ide.inspections.fixes.MakeContractAbstractFix
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.LinearizationImpossibleException
import me.serce.solidity.lang.types.SolContract
import me.serce.solidity.lang.types.SolFunctionTypeType
import me.serce.solidity.lang.types.SolType
import me.serce.solidity.lang.types.SolUnknown
import me.serce.solidity.lang.types.getSolType

/**
 * Mirrors solc's "Contract should be marked as abstract" error: a non-abstract contract that declares or inherits
 * functions without implementation. Contracts with unresolved or ambiguous bases are skipped.
 */
class ContractShouldBeAbstractInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitContractDefinition(o: SolContractDefinition) {
        if (o.contractType != ContractType.COMMON || o.isAbstract) {
          return
        }
        val identifier = o.identifier ?: return
        if (o.containingFile.allowsSolidityOlderThan(6)) {
          return
        }
        if (findUnimplementedFunctions(o).isNotEmpty()) {
          holder.registerProblem(
            identifier,
            "Contract should be marked as abstract",
            ProblemHighlightType.GENERIC_ERROR,
            MakeContractAbstractFix()
          )
        }
      }
    }
  }

  companion object {
    /** Functions without implementation in the linearization of [contract], empty when it can't be determined. */
    fun findUnimplementedFunctions(contract: SolContractDefinition): List<SolFunctionDefinition> {
      val linearization = try {
        SolContract(contract).linearize().map { it.ref }
      } catch (e: LinearizationImpossibleException) {
        return emptyList()
      }
      if (linearization.firstOrNull() != contract) {
        return emptyList()
      }
      val basesResolved = linearization.all { c ->
        c.supers.all { superType ->
          val resolved = superType.reference?.multiResolve() ?: emptyList()
          resolved.size == 1 && resolved.single() is SolContractDefinition
        }
      }
      if (!basesResolved) {
        return emptyList()
      }
      val functions = linearization.flatMap { it.functionDefinitionList }.filter { !it.isConstructor }
      val implemented = functions.filter { it.block != null }
      // public state variables implement external functions through their getters
      val getters = linearization.flatMap { it.stateVariableDeclarationList }
        .filter { it.visibility == Visibility.PUBLIC }
        .mapNotNull { it.name }
        .toSet()
      return functions
        .filter { it.isUnimplemented() }
        .filter { declaration ->
          declaration.name !in getters && implemented.none { sameSignature(declaration, it) }
        }
    }

    private fun SolFunctionDefinition.isUnimplemented(): Boolean =
      block == null && node.lastChildNode?.elementType == SolidityTokenTypes.SEMICOLON

    private fun sameSignature(f1: SolFunctionDefinition, f2: SolFunctionDefinition): Boolean {
      if (f1.specialFunction != f2.specialFunction || f1.name != f2.name) {
        return false
      }
      val p1 = f1.parameters
      val p2 = f2.parameters
      if (p1.size != p2.size) {
        return false
      }
      return p1.zip(p2).all { (a, b) -> typesMatch(getSolType(a.typeName), getSolType(b.typeName)) }
    }

    /** Types that can't be compared reliably match anything, so that an implementation is never missed. */
    private fun typesMatch(t1: SolType, t2: SolType): Boolean {
      if (t1 == SolUnknown || t2 == SolUnknown || t1 is SolFunctionTypeType || t2 is SolFunctionTypeType) {
        return true
      }
      return t1 == t2 || t1.toString() == t2.toString()
    }
  }
}
