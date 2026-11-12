package me.serce.solidity.ide.refactoring

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import me.serce.solidity.lang.core.SolidityLexer
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.resolve.function.SolFunctionResolver

/**
 * Renaming a function renames the whole override hierarchy with it: the functions it overrides, their other
 * overrides, and the functions overriding it. Otherwise renaming an interface function breaks every implementation.
 */
class RenameOverridesProcessor : RenamePsiElementProcessor() {
  override fun canProcessElement(element: PsiElement): Boolean =
    element is SolFunctionDefinition && !element.isConstructor

  override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
    val function = element as? SolFunctionDefinition ?: return
    hierarchy(function).filter { it != function }.forEach { allRenames[it] = newName }
  }

  private fun hierarchy(function: SolFunctionDefinition): Set<SolFunctionDefinition> {
    val result = linkedSetOf<SolFunctionDefinition>()
    val queue = ArrayDeque(listOf(function))
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (!result.add(next)) continue
      queue += SolFunctionResolver.collectOverridden(next)
      queue += SolFunctionResolver.collectOverrides(next)
    }
    return result
  }
}

/** Rename accepts only names that lex as one identifier, so a keyword or `1abc` is rejected. */
class SolNamesValidator : NamesValidator {
  override fun isKeyword(name: String, project: Project?): Boolean {
    val type = singleToken(name) ?: return false
    return type != SolidityTokenTypes.IDENTIFIER && name.all { it.isLetter() }
  }

  override fun isIdentifier(name: String, project: Project?): Boolean = singleToken(name) == SolidityTokenTypes.IDENTIFIER

  private fun singleToken(name: String) = SolidityLexer().run {
    start(name)
    val type = tokenType
    if (type == null || tokenEnd != name.length) null else type
  }
}
