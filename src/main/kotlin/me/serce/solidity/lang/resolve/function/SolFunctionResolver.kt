package me.serce.solidity.lang.resolve.function

import me.serce.solidity.ide.navigation.findAllImplementations
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefElement
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.parentOfType
import me.serce.solidity.lang.types.SolUnknown
import me.serce.solidity.lang.types.getSolType

object SolFunctionResolver {
  fun collectOverridden(func: SolFunctionDefElement, contract: SolContractDefinition? = func.parentOfType()): Collection<SolFunctionDefinition> {
    contract ?: return emptyList()
    val parents = SolOverrideResolver.linearizedParents(contract) ?: emptyList()
    return parents
      .flatMap { it.functionDefinitionList }
      .filter { signatureEquals(func, it) }
  }

  fun collectOverrides(func: SolFunctionDefinition): Collection<SolFunctionDefinition> = overridesIn(func).toList()

  fun hasOverrides(func: SolFunctionDefinition): Boolean = overridesIn(func).any()

  /** Functions of derived contracts with the same signature as [func]. */
  private fun overridesIn(func: SolFunctionDefinition): Sequence<SolFunctionDefinition> {
    val contract = func.parentOfType<SolContractDefinition>() ?: return emptySequence()
    return contract.findAllImplementations().asSequence()
      .flatMap { it.functionDefinitionList }
      .filter { signatureEquals(func, it) }
  }

  internal fun signatureEquals(f1: SolFunctionDefElement, f2: SolFunctionDefElement): Boolean {
    if (f1.name != f2.name) {
      return false
    }
    if (f1.parameters.size != f2.parameters.size) {
      return false
    }
    for ((p1, p2) in f1.parameters.zip(f2.parameters)) {
      val t1 = getSolType(p1.typeName)
      // unresolved types never match: better to miss an override than to report a false one
      if (t1 == SolUnknown || t1 != getSolType(p2.typeName)) {
        return false
      }
    }
    return true
  }
}
