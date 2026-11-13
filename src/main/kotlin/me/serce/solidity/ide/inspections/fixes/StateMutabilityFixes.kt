package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolStateMutabilitySpecifier
import me.serce.solidity.lang.psi.parentOfType

private fun PsiElement.enclosingFunction(): SolFunctionDefinition? =
  this as? SolFunctionDefinition ?: parentOfType()

private fun SolFunctionDefinition.specifier(keyword: String): SolStateMutabilitySpecifier? =
  stateMutabilitySpecifierList.firstOrNull { it.text == keyword }

private fun PsiElement.document(project: Project): Document? =
  PsiDocumentManager.getInstance(project).getDocument(containingFile)

/** Removes `view`/`pure` from the enclosing function. */
class RemoveStateMutabilityFix(private val keyword: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Remove '$keyword'"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val function = descriptor.psiElement.enclosingFunction() ?: return
    val specifier = function.specifier(keyword) ?: return
    val document = specifier.document(project) ?: return
    val start = (specifier.prevSibling as? PsiWhiteSpace)?.textRange?.startOffset ?: specifier.textRange.startOffset
    document.deleteString(start, specifier.textRange.endOffset)
  }
}

/** Replaces `view`/`pure` of the enclosing function with another keyword. */
class ChangeStateMutabilityFix(private val from: String, private val to: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Change '$from' to '$to'"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val function = descriptor.psiElement.enclosingFunction() ?: return
    val specifier = function.specifier(from) ?: return
    val document = specifier.document(project) ?: return
    document.replaceString(specifier.textRange.startOffset, specifier.textRange.endOffset, to)
  }
}

/** Adds `view`/`pure` after the visibility of the function, or after its parameters if there is no visibility. */
class AddStateMutabilityFix(private val keyword: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Add '$keyword'"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val function = descriptor.psiElement.enclosingFunction() ?: return
    val anchor = function.functionVisibilitySpecifierList.lastOrNull { it.visibilitySpecifier != null }
      ?: function.parameterListList.firstOrNull()
      ?: return
    val document = function.document(project) ?: return
    document.insertString(anchor.textRange.endOffset, " $keyword")
  }
}
