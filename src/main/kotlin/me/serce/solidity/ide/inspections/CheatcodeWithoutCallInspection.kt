package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import me.serce.solidity.ide.run.hasForgeTests
import me.serce.solidity.ide.run.isForgeTestFile
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.function.SolOverrideResolver
import me.serce.solidity.lang.resolve.ref.SolFunctionCallReference
import me.serce.solidity.lang.types.deInternalise
import me.serce.solidity.lang.types.isBuiltin

/**
 * `vm.expectRevert(...)` and `vm.prank(...)` apply to the next external call. Reports them in test contracts when no
 * statement after them, up to the end of the function, makes an external call, e.g. only an internal function follows,
 * whose revert happens at the test's own depth. Anything that can't be resolved counts as a possible call, so it isn't
 * reported.
 */
class CheatcodeWithoutCallInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : SolVisitor() {
    override fun visitFunctionCallExpression(o: SolFunctionCallExpression) {
      val cheatcode = o.cheatcodeName() ?: return
      if (cheatcode != "expectRevert" && cheatcode != "prank") return
      val statement = o.parent as? SolStatement ?: return
      val block = statement.parent as? SolBlock ?: return
      val contract = o.parentOfType<SolContractDefinition>() ?: return
      if (!isForgeTestFile(o.containingFile) && !contract.hasForgeTests()) return
      if (!nextCallIsMissing(statement, block)) return
      holder.registerProblem(o, "vm.$cheatcode is not followed by an external call")
    }
  }

  private fun nextCallIsMissing(statement: SolStatement, block: SolBlock): Boolean {
    var next: PsiElement? = statement.nextSibling
    while (next != null) {
      when {
        next is PsiErrorElement -> return false
        next is SolStatement && next.isCheatcodeCall() -> Unit
        // statements without external calls don't use up the cheatcode: forge applies it to the next external call
        next is SolStatement || next is SolUncheckedBlock -> if (CallAnalysis().classify(next) != Calls.NONE) return false
      }
      next = next.nextSibling
    }
    // at the end of a nested block the call may follow the block
    return block.parent is SolFunctionDefinition
  }
}

private enum class Calls { NONE, UNKNOWN, EXTERNAL }

/** `vm.<name>(...)` → `name`. */
private fun SolFunctionCallExpression.cheatcodeName(): String? {
  val callee = expression as? SolMemberAccessExpression ?: return null
  if ((callee.expression as? SolPrimaryExpression)?.varLiteral?.text != "vm") return null
  return callee.identifier?.text
}

private fun SolStatement.isCheatcodeCall(): Boolean = (firstChild as? SolFunctionCallExpression)?.cheatcodeName() != null

/** Whether code makes an external call, following internal calls into their bodies. */
private class CallAnalysis {
  private val visited = HashSet<SolFunctionDefinition>()

  fun classify(element: PsiElement, depth: Int = 0): Calls {
    if (PsiTreeUtil.hasErrorElements(element)) return Calls.UNKNOWN
    var result = Calls.NONE
    val elements = PsiTreeUtil.findChildrenOfAnyType(
      element, false, SolFunctionCallExpression::class.java, SolNewExpression::class.java, SolInlineAssemblyStatement::class.java
    ).toMutableList()
    if (element is SolFunctionCallExpression || element is SolNewExpression || element is SolInlineAssemblyStatement) elements += element
    for (e in elements) {
      val calls = when (e) {
        is SolInlineAssemblyStatement -> Calls.UNKNOWN
        is SolNewExpression -> classifyNew(e)
        is SolFunctionCallExpression -> if (e.expression is SolNewExpression) Calls.NONE else classifyCall(e, depth)
        else -> Calls.NONE
      }
      if (calls == Calls.EXTERNAL) return Calls.EXTERNAL
      if (calls > result) result = calls
    }
    return result
  }

  /** `new C()` deploys a contract; `new uint256[](n)` and `new bytes(n)` only allocate memory. */
  private fun classifyNew(element: SolNewExpression): Calls {
    val userType = when (val typeName = element.typeName) {
      is SolUserDefinedLocationTypeName -> typeName.userDefinedTypeName
      is SolUserDefinedTypeName -> typeName
      is SolArrayTypeName, is SolElementaryTypeName, is SolBytesArrayTypeName -> return Calls.NONE
      else -> null
    } ?: return Calls.UNKNOWN
    val resolved = SolResolver.resolveTypeNameUsingImports(userType)
    return if (resolved.isNotEmpty() && resolved.all { it is SolContractDefinition }) Calls.EXTERNAL else Calls.UNKNOWN
  }

  private fun classifyCall(call: SolFunctionCallExpression, depth: Int): Calls {
    // cheatcodes are calls to the cheatcode address, which expectRevert and prank don't count
    if (call.cheatcodeName() != null) return Calls.NONE
    val candidates = (call.reference as? SolFunctionCallReference)?.resolveFunctionCall().orEmpty()
    if (candidates.isEmpty()) return Calls.UNKNOWN
    val results = candidates.map { classifyCallable(call, it, depth) }.toSet()
    return results.singleOrNull() ?: Calls.UNKNOWN
  }

  private fun classifyCallable(call: SolFunctionCallExpression, callable: SolCallable, depth: Int): Calls {
    val callee = call.expression as? SolMemberAccessExpression
    return when (val element = callable.resolveElement()) {
      // elementary type conversions and builtins like `bytes.concat` or `wrap`/`unwrap`
      null -> Calls.NONE
      is SolFunctionDefinition -> when {
        element.isBuiltin() -> classifyBuiltin(element)
        callee != null && isExternalMemberCall(callee, element) -> Calls.EXTERNAL
        else -> classifyInternal(element, depth)
      }
      // a public getter called on a contract
      is SolStateVariableDeclaration ->
        if (callee != null && !element.isBuiltin() && callee.receiverKind() == Receiver.VALUE) Calls.EXTERNAL else Calls.UNKNOWN
      is SolContractDefinition, is SolStructDefinition, is SolEventDefinition, is SolErrorDefinition,
      is SolUserDefinedValueTypeDefinition -> Calls.NONE
      // function-typed variables and anything else
      else -> Calls.UNKNOWN
    }
  }

  private fun classifyBuiltin(function: SolFunctionDefinition): Calls {
    val owner = function.contract?.name?.let { deInternalise(it) } ?: return Calls.UNKNOWN
    val name = function.name ?: return Calls.UNKNOWN
    return when {
      owner == "Address" && name in ADDRESS_CALLS -> Calls.EXTERNAL
      // calls to precompiles
      owner == "Global" && name in PRECOMPILES -> Calls.UNKNOWN
      else -> Calls.NONE
    }
  }

  private fun isExternalMemberCall(callee: SolMemberAccessExpression, function: SolFunctionDefinition): Boolean {
    val owner = function.contract
    val publicFunction = function.visibility == Visibility.PUBLIC || function.visibility == Visibility.EXTERNAL
    return when (callee.receiverKind()) {
      // `super.f()`, `Base.f()` jump internally; `Lib.f()` is a delegatecall unless `f` is internal
      Receiver.SUPER -> false
      Receiver.TYPE -> owner?.contractType == ContractType.LIBRARY && publicFunction
      // `c.f()`, `this.f()`; on a value of another type a library function attached with `using for`
      Receiver.VALUE -> owner?.contractType != ContractType.LIBRARY || publicFunction
    }
  }

  private fun classifyInternal(function: SolFunctionDefinition, depth: Int): Calls {
    if (function in visited) return Calls.NONE
    val body = function.block ?: return Calls.UNKNOWN
    // an override or a modifier could make the call
    if (depth >= MAX_DEPTH || SolOverrideResolver.isVirtual(function) || function.modifiers.isNotEmpty()) return Calls.UNKNOWN
    visited += function
    return classify(body, depth + 1)
  }

  companion object {
    private const val MAX_DEPTH = 4
    private val ADDRESS_CALLS = setOf("call", "delegatecall", "staticcall", "transfer", "send")
    private val PRECOMPILES = setOf("ecrecover", "sha256", "ripemd160")
  }
}

private enum class Receiver { SUPER, TYPE, VALUE }

private fun SolMemberAccessExpression.receiverKind(): Receiver {
  val literal = (expression as? SolPrimaryExpression)?.varLiteral ?: return Receiver.VALUE
  return when (literal.text) {
    "super" -> Receiver.SUPER
    "this" -> Receiver.VALUE
    else -> {
      val targets = literal.reference?.multiResolve().orEmpty()
      if (targets.isNotEmpty() && targets.all { it is SolContractDefinition }) Receiver.TYPE else Receiver.VALUE
    }
  }
}
