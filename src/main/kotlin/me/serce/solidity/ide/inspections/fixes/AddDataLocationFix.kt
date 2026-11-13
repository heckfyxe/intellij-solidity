package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import me.serce.solidity.lang.psi.SolParameterDef
import me.serce.solidity.lang.psi.SolTypedDeclarationItem
import me.serce.solidity.lang.psi.SolVariableDeclaration

/** Inserts a data location right after the type of a parameter or a variable. */
class AddDataLocationFix(private val location: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Add '$location'"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val declaration = descriptor.psiElement
    val typeName = when (declaration) {
      is SolParameterDef -> declaration.typeName
      is SolVariableDeclaration -> declaration.typeName
      is SolTypedDeclarationItem -> declaration.typeName
      else -> null
    } ?: return
    val document = PsiDocumentManager.getInstance(project).getDocument(declaration.containingFile) ?: return
    document.insertString(typeName.textRange.endOffset, " $location")
  }
}
