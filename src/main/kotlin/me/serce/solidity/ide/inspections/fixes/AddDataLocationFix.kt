package me.serce.solidity.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import me.serce.solidity.lang.psi.SolTypedDeclaration

/** Inserts a data location right after the type of a parameter or a variable. */
class AddDataLocationFix(private val location: String) : LocalQuickFix {
  override fun getFamilyName(): String = "Add '$location'"

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val declaration = descriptor.psiElement as? SolTypedDeclaration ?: return
    val typeName = declaration.typeName ?: return
    val offset = typeName.textRange.endOffset
    editDocument(declaration) { it.insertString(offset, " $location") }
  }
}
