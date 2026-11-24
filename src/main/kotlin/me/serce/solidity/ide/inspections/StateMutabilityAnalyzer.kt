package me.serce.solidity.ide.inspections

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.ref.SolFunctionCallReference
import me.serce.solidity.lang.types.deInternalise
import me.serce.solidity.lang.types.isBuiltin

/** State mutability levels ordered from the most restrictive to the least restrictive one. */
enum class MutabilityLevel {
  PURE, VIEW, NONPAYABLE
}

/** An expression that is known to require at least [level]. */
class MutabilityEffect(val element: PsiElement, val level: MutabilityLevel)

class MutabilityAnalysis(
  /** Expressions that certainly read ([MutabilityLevel.VIEW]) or modify ([MutabilityLevel.NONPAYABLE]) the state. */
  val effects: List<MutabilityEffect>,
  /** The body uses something that might read the state but isn't reported as an error (e.g. storage pointers). */
  val mayRead: Boolean,
  /** The body uses `msg.value`, which requires `payable`. */
  val requiresPayable: Boolean,
  /** Something couldn't be analysed (unresolved references, inline assembly, unknown calls). */
  val incomplete: Boolean,
) {
  val modifiesState: Boolean get() = effects.any { it.level == MutabilityLevel.NONPAYABLE }
}

/**
 * A conservative approximation of solc's `ViewPureChecker`: it only reports effects it can prove with the resolved
 * PSI, everything uncertain is recorded as [MutabilityAnalysis.incomplete] or [MutabilityAnalysis.mayRead].
 */
object StateMutabilityAnalyzer {
  fun analyze(body: SolBlock): MutabilityAnalysis {
    val collector = Collector()
    body.accept(collector)
    return MutabilityAnalysis(collector.effects, collector.mayRead, collector.requiresPayable, collector.incomplete)
  }

  private enum class StorageKind { STATE, POINTER }

  private val PURE_GLOBALS = setOf(
    "assert", "require", "revert", "keccak256", "erc7201", "sha3", "sha256", "ripemd160", "ecrecover", "addmod", "mulmod"
  )
  private val ENVIRONMENT_VARIABLES = setOf("msg", "block", "tx")
  private val PURE_MSG_MEMBERS = setOf("data", "sig")
  private val ADDRESS_STATE_MEMBERS = setOf("balance", "code", "codehash")

  private class Collector : PsiRecursiveElementWalkingVisitor() {
    val effects = mutableListOf<MutabilityEffect>()
    var mayRead = false
    var requiresPayable = false
    var incomplete = false
    private val writtenRoots = HashSet<SolVarLiteral>()

    override fun visitElement(element: PsiElement) {
      when (element) {
        is SolInlineAssemblyStatement -> {
          incomplete = true
          return
        }
        is SolAssignmentExpression -> element.expressionList.firstOrNull()?.let { markWritten(it) }
        is SolTupleStatement -> element.expressionList.firstOrNull()?.let { markWritten(it) }
        is SolIncrementExpression -> element.expression?.let { markWritten(it) }
        is SolUnaryExpression -> if (element.firstChild?.text in setOf("++", "--", "delete")) {
          element.expression?.let { markWritten(it) }
        }
        is SolEmitStatement -> effects += MutabilityEffect(element, MutabilityLevel.NONPAYABLE)
        is SolNewExpression -> visitNew(element)
        is SolFunctionCallExpression -> visitCall(element)
        is SolMemberAccessExpression -> visitMemberAccess(element)
        is SolVarLiteral -> visitVarLiteral(element)
      }
      super.visitElement(element)
    }

    private fun markWritten(target: SolExpression) {
      if (target is SolSeqExpression) {
        target.expressionList.forEach { markWritten(it) }
        return
      }
      var current: SolExpression? = target
      var indirect = false
      while (true) {
        current = when (current) {
          is SolIndexAccessExpression -> current.expressionList.firstOrNull()
          is SolMemberAccessExpression -> current.expression
          else -> break
        }
        indirect = true
      }
      val literal = (current as? SolPrimaryExpression)?.varLiteral ?: return
      val writesState = when (storageKind(literal)) {
        StorageKind.STATE -> true
        // assigning a storage pointer itself doesn't touch the state, writing through it does
        StorageKind.POINTER -> indirect
        null -> false
      }
      if (writesState) {
        writtenRoots += literal
        effects += MutabilityEffect(target, MutabilityLevel.NONPAYABLE)
      }
    }

    private fun visitNew(element: SolNewExpression) {
      when (newTarget(element)) {
        NewTarget.MEMORY -> Unit
        NewTarget.CONTRACT -> {
          val call = element.parent as? SolFunctionCallExpression
          effects += MutabilityEffect(call ?: element, MutabilityLevel.NONPAYABLE)
        }
        NewTarget.UNKNOWN -> incomplete = true
      }
    }

    private fun visitCall(call: SolFunctionCallExpression) {
      val callee = call.expression
      if (callee is SolNewExpression) {
        // handled by visitNew; `new T[](n)` only allocates memory
        return
      }
      val reference = call.reference as? SolFunctionCallReference
      val candidates = reference?.resolveFunctionCall() ?: emptyList()
      if (candidates.isEmpty()) {
        incomplete = true
        return
      }
      var level = MutabilityLevel.NONPAYABLE
      for (candidate in candidates) {
        val candidateLevel = callableLevel(candidate)
        if (candidateLevel == null) {
          incomplete = true
          level = MutabilityLevel.PURE
        } else if (candidateLevel < level) {
          level = candidateLevel
        }
      }
      if (level != MutabilityLevel.PURE) {
        effects += MutabilityEffect(call, level)
      }
    }

    private fun callableLevel(callable: SolCallable): MutabilityLevel? {
      return when (val kind = calleeKind(callable)) {
        CalleeKind.Conversion, CalleeKind.TypeName -> MutabilityLevel.PURE
        is CalleeKind.Builtin -> builtinLevel(kind.owner, kind.name)
        is CalleeKind.Function -> functionLevel(kind.function)
        is CalleeKind.StateVariable -> if (kind.variable.isBuiltin()) null else MutabilityLevel.VIEW
        CalleeKind.Unknown -> null
      }
    }

    private fun functionLevel(function: SolFunctionDefinition): MutabilityLevel? {
      if (function.children.any { it is PsiErrorElement }) {
        return null
      }
      return when (function.mutability) {
        Mutability.PURE -> MutabilityLevel.PURE
        Mutability.VIEW -> MutabilityLevel.VIEW
        Mutability.PAYABLE, null -> MutabilityLevel.NONPAYABLE
      }
    }

    private fun builtinLevel(owner: String, name: String): MutabilityLevel? {
      return when (owner) {
        "Global" -> when (name) {
          "selfdestruct", "suicide" -> MutabilityLevel.NONPAYABLE
          "gasleft", "blockhash" -> MutabilityLevel.VIEW
          in PURE_GLOBALS -> MutabilityLevel.PURE
          else -> null
        }
        "Address" -> when (name) {
          "transfer", "send", "call", "delegatecall" -> MutabilityLevel.NONPAYABLE
          "staticcall" -> MutabilityLevel.VIEW
          else -> null
        }
        "Block" -> when (name) {
          "blockhash", "blobhash" -> MutabilityLevel.VIEW
          else -> null
        }
        // only storage arrays have push and pop
        "Array" -> if (name == "push" || name == "pop") MutabilityLevel.NONPAYABLE else null
        "Abi", "String" -> MutabilityLevel.PURE
        else -> null
      }
    }

    private fun visitMemberAccess(access: SolMemberAccessExpression) {
      if (isCallee(access)) {
        return
      }
      val memberName = access.identifier?.text ?: return
      val base = access.expression
      val baseLiteral = (base as? SolPrimaryExpression)?.varLiteral
      if (baseLiteral != null && baseLiteral.name in ENVIRONMENT_VARIABLES && isBuiltinGlobal(baseLiteral)) {
        when {
          baseLiteral.name == "msg" && memberName in PURE_MSG_MEMBERS -> Unit
          baseLiteral.name == "msg" && memberName == "value" -> requiresPayable = true
          else -> effects += MutabilityEffect(access, MutabilityLevel.VIEW)
        }
        return
      }
      val members = SolResolver.resolveMemberAccess(access)
      if (members.isEmpty()) {
        incomplete = true
        return
      }
      val elements = members.map { it.resolveElement() }
      if (elements.all { it is SolStateVariableDeclaration && it.isBuiltin() && it.isAddressStateMember() }) {
        effects += MutabilityEffect(access, MutabilityLevel.VIEW)
      }
    }

    private fun visitVarLiteral(literal: SolVarLiteral) {
      val primary = literal.parent as? SolPrimaryExpression
      if (primary != null && isCallee(primary)) {
        // the call itself is classified by visitCall
        return
      }
      when (literal.name) {
        "this" -> {
          if (!isSelectorAccess(primary)) {
            effects += MutabilityEffect(literal, MutabilityLevel.VIEW)
          }
          return
        }
        "super" -> return
      }
      val resolved = literal.reference?.multiResolve() ?: emptyList()
      if (resolved.isEmpty()) {
        incomplete = true
        return
      }
      if (resolved.any { it.isBuiltin() }) {
        // environment variables (`msg`, `block`, ...) are handled by visitMemberAccess
        return
      }
      val stateVariables = resolved.filterIsInstance<SolStateVariableDeclaration>()
      when {
        stateVariables.isEmpty() -> if (resolved.any { it.isStoragePointer() }) {
          mayRead = true
        }
        stateVariables.size != resolved.size -> incomplete = true
        stateVariables.all { it.mutability == VariableMutability.CONSTANT } -> Unit
        stateVariables.any { it.mutability != null } -> mayRead = true
        literal !in writtenRoots -> effects += MutabilityEffect(literal, MutabilityLevel.VIEW)
      }
    }

    private fun storageKind(literal: SolVarLiteral): StorageKind? {
      val resolved = literal.reference?.multiResolve() ?: return null
      if (resolved.isEmpty() || resolved.any { it.isBuiltin() }) {
        return null
      }
      if (resolved.all { it is SolStateVariableDeclaration && it.mutability == null }) {
        return StorageKind.STATE
      }
      if (resolved.all { it.isStoragePointer() }) {
        return StorageKind.POINTER
      }
      return null
    }

    private fun isBuiltinGlobal(literal: SolVarLiteral): Boolean {
      val resolved = literal.reference?.multiResolve() ?: return false
      return resolved.isNotEmpty() && resolved.all { it is SolStateVariableDeclaration && it.isBuiltin() }
    }

    private fun isCallee(expression: SolExpression): Boolean {
      val call = expression.parent as? SolFunctionCallExpression ?: return false
      return call.expression == expression
    }

    /** `this.f.selector` doesn't read the state. */
    private fun isSelectorAccess(primary: SolPrimaryExpression?): Boolean {
      val functionAccess = primary?.parent as? SolMemberAccessExpression ?: return false
      val selectorAccess = functionAccess.parent as? SolMemberAccessExpression ?: return false
      return selectorAccess.identifier?.text == "selector"
    }
  }

  private fun SolStateVariableDeclaration.isAddressStateMember(): Boolean {
    val owner = outerContract()?.name?.let { deInternalise(it) }
    return owner == "Address" && name in ADDRESS_STATE_MEMBERS
  }

}

