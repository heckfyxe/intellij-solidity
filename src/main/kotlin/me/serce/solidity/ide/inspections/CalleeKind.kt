package me.serce.solidity.ide.inspections

import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.types.deInternalise
import me.serce.solidity.lang.types.isBuiltin

/** What a call runs, for the analyses that follow calls (state mutability, cheatcodes). */
internal sealed interface CalleeKind {
  /** An elementary type conversion or a built-in without a declaration, like `bytes.concat` or `wrap`/`unwrap`. */
  object Conversion : CalleeKind

  /** A contract conversion, a struct constructor, an event, an error or a user-defined value type: no code runs. */
  object TypeName : CalleeKind

  /** A built-in function: [name] of the internal type [owner] (`Global`, `Address`, `Array`, ...). */
  class Builtin(val owner: String, val name: String) : CalleeKind

  /** A function declared in user code. */
  class Function(val function: SolFunctionDefinition) : CalleeKind

  /** The getter of a public state variable, or a built-in member. */
  class StateVariable(val variable: SolStateVariableDeclaration) : CalleeKind

  /** A function-typed variable, or anything else that can't be told. */
  object Unknown : CalleeKind
}

internal fun calleeKind(callable: SolCallable): CalleeKind = when (val element = callable.resolveElement()) {
  null -> CalleeKind.Conversion
  is SolFunctionDefinition -> if (element.isBuiltin()) builtinKind(element) else CalleeKind.Function(element)
  is SolStateVariableDeclaration -> CalleeKind.StateVariable(element)
  is SolContractDefinition, is SolStructDefinition, is SolEventDefinition, is SolErrorDefinition,
  is SolUserDefinedValueTypeDefinition -> CalleeKind.TypeName
  else -> CalleeKind.Unknown
}

private fun builtinKind(function: SolFunctionDefinition): CalleeKind {
  val owner = function.contract?.name?.let { deInternalise(it) } ?: return CalleeKind.Unknown
  val name = function.name ?: return CalleeKind.Unknown
  return CalleeKind.Builtin(owner, name)
}

/** What `new T` creates. */
internal enum class NewTarget {
  /** `new uint256[](n)`, `new bytes(n)`: memory only. */
  MEMORY,
  /** `new C()`: deploys a contract. */
  CONTRACT,
  UNKNOWN
}

internal fun newTarget(element: SolNewExpression): NewTarget {
  val userType = when (val typeName = element.typeName) {
    is SolUserDefinedLocationTypeName -> typeName.userDefinedTypeName
    is SolUserDefinedTypeName -> typeName
    is SolArrayTypeName, is SolElementaryTypeName, is SolBytesArrayTypeName -> return NewTarget.MEMORY
    else -> null
  } ?: return NewTarget.UNKNOWN
  val resolved = SolResolver.resolveTypeNameUsingImports(userType)
  return if (resolved.isNotEmpty() && resolved.all { it is SolContractDefinition }) NewTarget.CONTRACT else NewTarget.UNKNOWN
}
