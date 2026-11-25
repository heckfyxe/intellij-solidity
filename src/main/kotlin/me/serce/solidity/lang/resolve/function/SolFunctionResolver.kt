package me.serce.solidity.lang.resolve.function

import me.serce.solidity.ide.navigation.findAllImplementations
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefElement
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.findContract
import me.serce.solidity.lang.types.hasSameSignature

object SolFunctionResolver {
  fun collectOverridden(func: SolFunctionDefElement, contract: SolContractDefinition? = func.findContract()): Collection<SolFunctionDefinition> {
    contract ?: return emptyList()
    val parents = SolOverrideResolver.linearizedParents(contract) ?: emptyList()
    return parents
      .flatMap { it.functionDefinitionList }
      .filter { func.hasSameSignature(it) }
  }

  fun collectOverrides(func: SolFunctionDefinition): Collection<SolFunctionDefinition> = overridesIn(func).toList()

  /** Functions of derived contracts with the same signature as [func]. */
  private fun overridesIn(func: SolFunctionDefinition): Sequence<SolFunctionDefinition> {
    val contract = func.findContract() ?: return emptySequence()
    return contract.findAllImplementations().asSequence()
      .flatMap { it.functionDefinitionList }
      .filter { func.hasSameSignature(it) }
  }
}
