package me.serce.solidity.ide.inspections

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.core.SolidityVersion
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.findContract
import me.serce.solidity.lang.types.isBuiltin

internal object SolVersions {
  val V0_5_0 = SolidityVersion.of("0.5.0")
  val V0_6_0 = SolidityVersion.of("0.6.0")
  val V0_7_0 = SolidityVersion.of("0.7.0")
  val V0_8_0 = SolidityVersion.of("0.8.0")
  val V0_8_18 = SolidityVersion.of("0.8.18")
  val V0_8_31 = SolidityVersion.of("0.8.31")
}

/**
 * Returns true when the file's `pragma solidity` admits a compiler older than [version].
 *
 * Inspections that mirror errors of modern compilers use it to stay silent on legacy code, where the same constructs
 * were legal (e.g. contracts with unimplemented functions were implicitly abstract before 0.6.0). A file without a
 * (parseable) `pragma solidity` is treated as modern code.
 */
fun PsiFile.allowsSolidityOlderThan(version: SolidityVersion): Boolean = solidityVersionRange()?.isAtLeast(version) == false

internal fun PsiElement.isInsideAssembly(): Boolean =
  PsiTreeUtil.getParentOfType(this, SolInlineAssemblyStatement::class.java, SolPragmaDirective::class.java) != null

/**
 * The enclosing contract inherits from a contract that can't be resolved, so a name that looks
 * like a global might actually be declared there.
 */
private fun PsiElement.hasUnresolvedInheritance(): Boolean {
  val contract = findContract() ?: return false
  return contract.collectSupers.any { it.reference?.resolve() == null }
}

/**
 * The reference of [element] does not point to anything declared in user code,
 * i.e. it resolves only to compiler built-ins or to nothing at all.
 */
internal fun refersToBuiltinOnly(element: SolReferenceElement): Boolean {
  val targets = element.reference?.multiResolve() ?: return false
  if (targets.any { !it.isBuiltin() }) return false
  return !element.hasUnresolvedInheritance()
}

/** `name` is a plain identifier expression referring to the global [name] (not shadowed by user code). */
internal fun SolExpression?.isGlobalIdentifier(name: String): Boolean {
  val varLiteral = (this as? SolPrimaryExpression)?.varLiteral ?: return false
  return varLiteral.text == name && refersToBuiltinOnly(varLiteral)
}

/** `qualifier.member` where `qualifier` is the global [qualifier], e.g. `block.difficulty`. */
internal fun SolMemberAccessExpression.isGlobalMember(qualifier: String, member: String): Boolean =
  identifier?.text == member && expression.isGlobalIdentifier(qualifier)

/** The called expression of a call `f(...)` when `f` is a plain identifier. */
internal val SolFunctionCallExpression.calleeVarLiteral: SolVarLiteral?
  get() = (expression as? SolPrimaryExpression)?.varLiteral
