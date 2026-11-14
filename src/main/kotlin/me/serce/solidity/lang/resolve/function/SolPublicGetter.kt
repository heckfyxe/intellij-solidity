package me.serce.solidity.lang.resolve.function

import me.serce.solidity.lang.core.SolidityVersion
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.*

/**
 * The getter solc generates for a public state variable, and when such a variable can implement a function.
 *
 * `mapping(K => V)` gives `f(K) returns (V)` and `T[]` gives `f(uint256) returns (T)`, nested from the outside in.
 * The getter is `view`, so it implements view or nonpayable external functions; a struct (or whole array) return
 * never matches, since the getter returns the struct's members (or one element).
 */
object SolPublicGetter {
  /** Public state variables can override external functions since solc 0.6.0. */
  val OVERRIDE_SINCE: SolidityVersion = SolidityVersion.of("0.6.0")

  private class Getter(val parameters: List<SolType>, val returns: SolType)

  private fun getter(variable: SolStateVariableDeclaration): Getter? {
    if (variable.visibility != Visibility.PUBLIC) return null
    val parameters = ArrayList<SolType>()
    var type: SolTypeName? = variable.typeName
    while (true) {
      type = when (type) {
        is SolMappingTypeName -> {
          val (key, value) = type.typeNameList.takeIf { it.size == 2 } ?: return null
          parameters.add(getSolType(key))
          value
        }
        is SolArrayTypeName -> {
          parameters.add(SolInteger.UINT_256)
          type.typeName
        }
        else -> break
      }
    }
    val returns = getSolType(type)
    if (returns is SolStruct || returns == SolUnknown) return null
    return Getter(parameters, returns)
  }

  /** Whether [variable]'s generated getter implements [function] (name, parameter and return types, mutability). */
  fun implements(variable: SolStateVariableDeclaration, function: SolFunctionDefinition): Boolean {
    if (variable.name == null || variable.name != function.name || function.specialFunction != null) return false
    if (!getterCanOverride(function)) return false
    val getter = getter(variable) ?: return false
    val parameters = function.parameters.map { getSolType(it.typeName) }
    val returns = function.returns?.parameterDefList?.map { getSolType(it.typeName) } ?: return false
    return returns.size == 1 && sameType(getter.returns, returns[0]) &&
      parameters.size == getter.parameters.size && parameters.zip(getter.parameters).all { (a, b) -> sameType(a, b) }
  }

  /** A public state variable of [contract] or one of its bases whose getter implements [function]. */
  fun implementingVariable(contract: SolContractDefinition, function: SolFunctionDefinition): SolStateVariableDeclaration? {
    val contracts = listOf(contract) + (SolOverrideResolver.linearizedParents(contract) ?: emptyList())
    return contracts.asSequence().flatMap { it.stateVariableDeclarationList.asSequence() }.firstOrNull { implements(it, function) }
  }

  /**
   * Whether [function] is a getter to implement with a public state variable in [contract]: an external view function
   * (a nonpayable one could be overridden too, but isn't a getter) returning one value that isn't a struct or array
   * (`string`/`bytes` are fine), whose parameters can all be mapping keys, in a file whose pragma allows only solc
   * versions that support it.
   */
  fun canImplementAsVariable(function: SolFunctionDefinition, contract: SolContractDefinition): Boolean {
    if (function.name == null || function.specialFunction != null || !getterCanOverride(function)) return false
    if (function.mutability != Mutability.VIEW) return false
    val range = contract.containingFile.solidityVersionRange()
    if (range != null && !range.isAtLeast(OVERRIDE_SINCE)) return false
    val returns = function.returns?.parameterDefList ?: return false
    if (returns.size != 1 || !isSingleValue(getSolType(returns[0].typeName))) return false
    return function.parameters.all { isSingleValue(getSolType(it.typeName)) }
  }

  /** `mapping(K1 => mapping(K2 => V)) public override name;` for [function] (see [canImplementAsVariable]). */
  fun variableText(function: SolFunctionDefinition, override: String): String {
    // a parameter's data location belongs to the parameter, not its type name
    val value: String = function.returns!!.parameterDefList[0].typeName.text
    val type: String = function.parameters.foldRight(value) { p, acc -> "mapping(${p.typeName.text} => $acc)" }
    return listOf(type, "public", override, function.name ?: "").filter { it.isNotEmpty() }.joinToString(" ") + ";"
  }

  /** External only: a public variable can't override a public function. View or nonpayable, never payable or pure. */
  private fun getterCanOverride(function: SolFunctionDefinition): Boolean =
    function.visibility == Visibility.EXTERNAL && (function.mutability == null || function.mutability == Mutability.VIEW)

  /** A type that is a mapping key and a getter's single return value: not a struct, array (but `bytes`) or mapping. */
  private fun isSingleValue(type: SolType): Boolean = when (type) {
    is SolStruct, is SolMapping, is SolTuple, SolUnknown -> false
    is SolArray -> type == SolBytes
    else -> true
  }

  private fun sameType(a: SolType, b: SolType): Boolean = a == b || a.toString() == b.toString()
}
