package me.serce.solidity.lang.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.stubs.SolidityFileStub

fun PsiElement.rangeRelativeTo(ancestor: PsiElement): TextRange {
  check(ancestor.textRange.contains(textRange))
  return textRange.shiftRight(-ancestor.textRange.startOffset)
}

inline fun <reified T : PsiElement> PsiElement.childOfType(strict: Boolean = true): T? =
  PsiTreeUtil.findChildOfType(this, T::class.java, strict)

val PsiElement.parentRelativeRange: TextRange
  get() = rangeRelativeTo(parent)

val PsiElement.ancestors: Sequence<PsiElement> get() = generateSequence(this) { it.parent }

val PsiElement.elementType: IElementType
  get() = if (this is SolidityFile) SolidityFileStub.Type else PsiUtilCore.getElementType(this)

inline fun <reified T : PsiElement> PsiElement.parentOfType(strict: Boolean = true, minStartOffset: Int = -1): T? =
  PsiTreeUtil.getParentOfType(this, T::class.java, strict, minStartOffset)

/** The contract, interface or library [this] is in, or [this] itself when it is one. Walks stubs when it can. */
fun PsiElement.findContract(): SolContractDefinition? =
  this as? SolContractDefinition ?: PsiTreeUtil.getStubOrPsiParentOfType(this, SolContractDefinition::class.java)

/**
 * The contract a member is declared in (functions, modifiers, state variables, events, ..., and the items of
 * enums and structs declared in it); null for top-level declarations and locals.
 */
fun SolNamedElement.outerContract(): SolContractDefinition? =
  parent?.takeIf { it is SolContractDefinition || it is SolEnumDefinition || it is SolStructDefinition }?.findContract()

/** A local variable or a parameter declared with the `storage` data location. */
fun PsiElement.isStoragePointer(): Boolean = (this as? SolTypedDeclaration)?.dataLocation?.text == "storage"

/** The elements a name-keyed stub index holds for [name]. */
inline fun <reified T : PsiElement> StubIndexKey<String, T>.elements(
  name: String,
  project: Project,
  scope: GlobalSearchScope? = null,
): Collection<T> = StubIndex.getElements(this, name, project, scope, T::class.java)
