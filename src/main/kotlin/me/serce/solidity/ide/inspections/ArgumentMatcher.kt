package me.serce.solidity.ide.inspections

import me.serce.solidity.lang.psi.SolExpression
import me.serce.solidity.lang.types.SolType
import me.serce.solidity.lang.types.SolUnknown
import me.serce.solidity.lang.types.displayName
import me.serce.solidity.lang.types.type

/**
 * Checks call arguments against candidate signatures and remembers why the last candidates were rejected.
 * A type mismatch takes precedence over an argument count mismatch in [message].
 */
internal class ArgumentMatcher(private val args: List<SolExpression>) {
  private var wrongNumberOfArgs = ""
  private var wrongTypes = ""

  val message: String
    get() = wrongTypes.ifEmpty { wrongNumberOfArgs }

  /**
   * Whether [args] fit [params]. A null [params] means the argument count does not fit a signature
   * of [declaredCount] parameters.
   */
  fun <P> matches(params: List<P>?, declaredCount: Int, typeOf: (P) -> SolType): Boolean {
    if (params == null) {
      wrongNumberOfArgs = "Expected $declaredCount argument${if (declaredCount > 1) "s" else ""}, but got ${args.size}"
      return false
    }
    return args.withIndex().all { (index, arg) ->
      val param = params.getOrNull(index) ?: return@all false
      val expType = typeOf(param)
      val actType = arg.type
      expType == SolUnknown || actType == SolUnknown || expType.isAssignableFrom(actType).also {
        if (!it) {
          wrongTypes = "Argument of type '${actType.displayName()}' is not assignable to parameter of type '$expType'"
        }
      }
    }
  }
}
