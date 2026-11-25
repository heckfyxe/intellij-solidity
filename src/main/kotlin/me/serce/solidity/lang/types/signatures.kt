package me.serce.solidity.lang.types

import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import me.serce.solidity.lang.psi.SolFunctionDefElement
import me.serce.solidity.lang.psi.SolStructDefinition
import me.serce.solidity.lang.psi.outerContract

/**
 * The canonical signature of a function, the one its selector is computed from and forge's `--sig` takes:
 * `transfer(address,uint256)`. `receive` and `fallback` are `receive()` and `fallback()` whatever their parameters.
 *
 * Null when a parameter type is unknown. Matching on it is strict: a null signature equals nothing, so an unresolved
 * type never makes two functions the same. Code that would report a *missing* match (an unimplemented function)
 * skips functions without a signature instead, so neither direction reports a false problem.
 */
fun SolFunctionDefElement.canonicalSignature(): String? = CachedValuesManager.getCachedValue(this) {
  CachedValueProvider.Result.create(computeSignature(this), PsiModificationTracker.MODIFICATION_COUNT)
}

/** Whether both functions have the same known [canonicalSignature]. */
fun SolFunctionDefElement.hasSameSignature(other: SolFunctionDefElement): Boolean =
  canonicalSignature()?.let { it == other.canonicalSignature() } ?: false

private fun computeSignature(function: SolFunctionDefElement): String? {
  function.specialFunction?.let { return "${it.name.lowercase()}()" }
  val name = function.name ?: return null
  val types = function.parameters.map { canonicalTypeName(getSolType(it.typeName)) ?: return null }
  return "$name(${types.joinToString(",")})"
}

/**
 * The ABI name of [type]: `uint256`, `address` for contracts, `uint8` for enums, the underlying type of a user-defined
 * value type, `(uint256,address)` for a struct. Types without an ABI encoding keep a Solidity name (`mapping(...)`, a
 * recursive struct by its name); null for unknown types.
 */
fun canonicalTypeName(type: SolType): String? = canonicalTypeName(type, emptySet())

private fun canonicalTypeName(type: SolType, structs: Set<SolStructDefinition>): String? = when (type) {
  is SolInteger, SolBoolean, is SolString, SolBytes, is SolFixedBytes -> type.toString()
  is SolAddress, is SolContract -> "address"
  is SolEnum -> "uint8"
  is SolUserDefinedValueTypeType -> canonicalTypeName(type.elementaryType, structs)
  is SolStruct -> structName(type.ref, structs)
  is SolArray.SolStaticArray -> canonicalTypeName(type.type, structs)?.let { "$it[${type.size}]" }
  is SolArray.SolDynamicArray -> canonicalTypeName(type.type, structs)?.let { "$it[]" }
  is SolMapping -> {
    val from = canonicalTypeName(type.from, structs)
    val to = canonicalTypeName(type.to, structs)
    if (from != null && to != null) "mapping($from=>$to)" else null
  }
  is SolFunctionTypeType -> "function"
  else -> null
}

private fun structName(struct: SolStructDefinition, outer: Set<SolStructDefinition>): String? {
  val name = listOfNotNull(struct.outerContract()?.name, struct.name ?: return null).joinToString(".")
  if (struct in outer) return name
  val members = struct.variableDeclarationList.map { canonicalTypeName(getSolType(it.typeName), outer + struct) ?: return name }
  return "(${members.joinToString(",")})"
}
