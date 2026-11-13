package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.hints.comments
import me.serce.solidity.ide.inspections.fixes.MemorySafeAssemblyFix
import me.serce.solidity.ide.inspections.fixes.ReplaceTextFix
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.SolAddress
import me.serce.solidity.lang.types.SolContract
import me.serce.solidity.lang.types.SolType
import me.serce.solidity.lang.types.type

private fun SolType.isUserContract() = this is SolContract && !builtin

/**
 * Reports constructs the compiler warns about as deprecated in every Solidity version the file's
 * `pragma solidity` allows. Files without a version pragma are not checked.
 *
 * Sources: the Solidity compiler changelog (0.8.18, 0.8.31).
 */
class DeprecatedLanguageFeatureInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val range = holder.file.solidityVersionRange() ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!range.isAtLeast(SolVersions.V0_8_18)) return PsiElementVisitor.EMPTY_VISITOR
    val since0831 = range.isAtLeast(SolVersions.V0_8_31)

    fun report(element: PsiElement, message: String, vararg fixes: LocalQuickFix) {
      holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, *fixes)
    }

    return object : SolVisitor() {
      override fun visitFunctionCallExpression(o: SolFunctionCallExpression) {
        val callee = o.calleeVarLiteral ?: return
        if (callee.text == "selfdestruct" && refersToBuiltinOnly(callee)) {
          report(callee, "'selfdestruct' is deprecated since Solidity 0.8.18 (EIP-6049); since EIP-6780 it no longer deletes the contract code")
        }
      }

      override fun visitMemberAccessExpression(o: SolMemberAccessExpression) {
        val identifier = o.identifier ?: return
        when (identifier.text) {
          "difficulty" -> if (o.isGlobalMember("block", "difficulty")) {
            report(identifier, "'block.difficulty' is deprecated since Solidity 0.8.18, use 'block.prevrandao'",
              ReplaceTextFix(identifier, "prevrandao"))
          }
          "send", "transfer" -> if (since0831 && o.parent is SolFunctionCallExpression &&
            o.expression.type is SolAddress && refersToBuiltinOnly(o)) {
            report(identifier, "'${identifier.text}' on addresses is deprecated since Solidity 0.8.31, use 'call{value: ...}(\"\")'")
          }
        }
      }

      override fun visitPragmaDirective(o: SolPragmaDirective) {
        if (since0831 && o.identifier.text == "abicoder" && o.pragmaAll?.text?.trim() == "v1") {
          report(o, "ABI coder v1 is deprecated since Solidity 0.8.31")
        }
      }

      override fun visitEqExpression(o: SolEqExpression) = checkContractComparison(o)

      override fun visitCompExpression(o: SolCompExpression) = checkContractComparison(o)

      /** `a == b` where both are contracts: compare `address(a) == address(b)` instead. */
      private fun checkContractComparison(o: SolExpression) {
        if (!since0831) return
        val operands = PsiTreeUtil.getChildrenOfTypeAsList(o, SolExpression::class.java)
        if (operands.size != 2) return
        val (left, right) = operands
        if (!left.type.isUserContract() || !right.type.isUserContract()) return
        val operator = PsiTreeUtil.skipWhitespacesAndCommentsForward(left) ?: return
        report(o, "Comparison of variables of contract type is deprecated since Solidity 0.8.31, compare their addresses",
          ReplaceTextFix(o, "address(${left.text}) ${operator.text} address(${right.text})", "Compare addresses"))
      }

      override fun visitInlineAssemblyStatement(o: SolInlineAssemblyStatement) {
        if (!since0831) return
        val comments = (o.parent as? SolStatement ?: o).comments().sortedBy { it.textRange.startOffset }
        val tagIndex = comments.indices.firstOrNull { i ->
          comments[i].elementType == SolidityTokenTypes.NAT_SPEC_TAG && comments[i].text == "@solidity" &&
            comments.getOrNull(i + 1)?.text?.trim()?.startsWith("memory-safe-assembly") == true
        } ?: return
        report(comments[tagIndex], "The '@solidity memory-safe-assembly' comment is deprecated since Solidity 0.8.31, use 'assembly (\"memory-safe\")'",
          MemorySafeAssemblyFix(o))
      }

      override fun visitModifierDefinition(o: SolModifierDefinition) {
        if (!since0831) return
        for (virtual in o.virtualSpecifierList) {
          report(virtual, "Virtual modifiers are deprecated since Solidity 0.8.31")
        }
      }
    }
  }
}
