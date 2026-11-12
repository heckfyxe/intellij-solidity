package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.SolVisitor

/**
 * Reports identifiers that are reserved keywords. Only checked when the file's `pragma solidity`
 * guarantees 0.5.0 or later: every word below is reserved in all versions since then.
 *
 * Source: the reserved keywords of the compiler's token list (`liblangutil/Token.h`). `byte`, `var` and
 * `unchecked` are lexed as keywords by the plugin and therefore never appear as identifiers.
 */
class ReservedKeywordInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val range = holder.file.solidityVersionRange() ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!range.isAtLeast(SolVersions.V0_5_0)) return PsiElementVisitor.EMPTY_VISITOR

    return object : SolVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element.elementType != SolidityTokenTypes.IDENTIFIER) return
        val text = element.text
        if (text in RESERVED_KEYWORDS && !element.isInsideAssembly()) {
          holder.registerProblem(element, "'$text' is a reserved keyword and can't be used as an identifier",
            ProblemHighlightType.GENERIC_ERROR)
        }
      }
    }
  }

  companion object {
    val RESERVED_KEYWORDS = setOf(
      "after", "alias", "apply", "auto", "case", "copyof", "default", "define", "final", "implements",
      "in", "inline", "let", "macro", "match", "mutable", "null", "of", "partial", "promise", "reference",
      "relocatable", "sealed", "sizeof", "static", "supports", "switch", "typedef", "typeof"
    )
  }
}
