package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.inspections.fixes.ReplaceTextFix
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.core.SolidityVersion
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.SolAddress
import me.serce.solidity.lang.types.type

/**
 * Reports language features that were removed in a Solidity version the file's
 * `pragma solidity` guarantees. Files without a version pragma are not checked.
 *
 * Sources: the 0.5.0/0.7.0/0.8.0 breaking changes pages of the Solidity documentation and the compiler changelog.
 */
class RemovedLanguageFeatureInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val range = holder.file.solidityVersionRange() ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!range.isAtLeast(SolVersions.V0_5_0)) return PsiElementVisitor.EMPTY_VISITOR
    fun since(version: SolidityVersion) = range.isAtLeast(version)

    fun report(element: PsiElement, message: String, vararg fixes: LocalQuickFix) {
      holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR, *fixes)
    }

    return object : SolVisitor() {
      override fun visitElement(element: PsiElement) {
        // `var x = ...;` / `var (a, b) = ...;`
        if (element.elementType == SolidityTokenTypes.VAR && !element.isInsideAssembly()) {
          report(element, "'var' was removed in Solidity 0.5.0, declare the type explicitly")
        }
      }

      override fun visitThrowSt(o: SolThrowSt) {
        report(o, "'throw' was removed in Solidity 0.5.0, use 'revert()', 'require()' or 'assert()'",
          ReplaceTextFix(o, "revert()"))
      }

      override fun visitNumberUnit(o: SolNumberUnit) {
        when (o.text) {
          "years" -> report(o, "The 'years' unit was removed in Solidity 0.5.0")
          "szabo", "finney" -> if (since(SolVersions.V0_7_0)) {
            report(o, "The '${o.text}' denomination was removed in Solidity 0.7.0")
          }
        }
      }

      override fun visitUnaryExpression(o: SolUnaryExpression) {
        if (o.firstChild.elementType != SolidityTokenTypes.PLUS) return
        val operand = o.expression ?: return
        report(o.firstChild, "Unary '+' was removed in Solidity 0.5.0", ReplaceTextFix(o, operand.text, "Remove unary '+'"))
      }

      override fun visitElementaryTypeName(o: SolElementaryTypeName) {
        if (o.text == "byte" && since(SolVersions.V0_8_0) && !o.isInsideAssembly()) {
          report(o, "The type 'byte' was removed in Solidity 0.8.0, use 'bytes1'", ReplaceTextFix(o, "bytes1"))
        }
      }

      override fun visitVarLiteral(o: SolVarLiteral) {
        if (o.text == "now" && since(SolVersions.V0_7_0) && o.parent is SolPrimaryExpression && refersToBuiltinOnly(o)) {
          report(o, "'now' was removed in Solidity 0.7.0, use 'block.timestamp'", ReplaceTextFix(o, "block.timestamp"))
        }
      }

      override fun visitFunctionCallExpression(o: SolFunctionCallExpression) {
        val callee = o.calleeVarLiteral
        when (callee?.text) {
          "sha3" -> if (refersToBuiltinOnly(callee)) {
            report(callee, "'sha3' was removed in Solidity 0.5.0, use 'keccak256'", ReplaceTextFix(callee, "keccak256"))
          }
          "suicide" -> if (refersToBuiltinOnly(callee)) {
            report(callee, "'suicide' was removed in Solidity 0.5.0, use 'selfdestruct'", ReplaceTextFix(callee, "selfdestruct"))
          }
        }
        if (o.parent is SolStatement && callee != null) {
          val targets = callee.reference?.multiResolve().orEmpty()
          if (targets.isNotEmpty() && targets.all { it is SolEventDefinition }) {
            report(o, "Events must be emitted with 'emit' since Solidity 0.5.0", ReplaceTextFix(o, "emit ${o.text}", "Add 'emit'"))
          }
        }
        if (since(SolVersions.V0_7_0)) {
          checkCallOptionsSyntax(o)
        }
      }

      /** `x.f.value(v)(args)` / `x.f.gas(g)(args)` became `x.f{value: v, gas: g}(args)` in 0.7.0. */
      private fun checkCallOptionsSyntax(call: SolFunctionCallExpression) {
        val options = mutableListOf<Pair<String, String>>()
        var current: SolExpression? = call.expression
        var simple = true
        var firstOption: PsiElement? = null
        while (current is SolFunctionCallExpression) {
          val member = current.expression as? SolMemberAccessExpression ?: break
          val name = member.identifier?.text
          if (name != "value" && name != "gas") break
          if (!refersToBuiltinOnly(member)) return
          val invocation = current.functionInvocation
          val args = invocation.functionCallArguments?.expressionList.orEmpty()
          if (invocation.mapExpression != null || args.size != 1) simple = false
          options.add(name to (args.firstOrNull()?.text ?: ""))
          firstOption = member.identifier
          current = member.expression
        }
        if (options.isEmpty() || current == null || firstOption == null) return
        val message = "The '.value(...)'/'.gas(...)' call syntax was removed in Solidity 0.7.0, use call options '{value: ..., gas: ...}'"
        val fixes = if (simple && options.map { it.first }.distinct().size == options.size) {
          val args = call.functionInvocation.text
          val replacement = "${current.text}{${options.reversed().joinToString(", ") { "${it.first}: ${it.second}" }}}$args"
          arrayOf<LocalQuickFix>(ReplaceTextFix(call, replacement, "Convert to call options"))
        } else emptyArray()
        report(firstOption, message, *fixes)
      }

      override fun visitMemberAccessExpression(o: SolMemberAccessExpression) {
        val name = o.identifier?.text ?: return
        when (name) {
          "gas" -> if (o.isGlobalMember("msg", "gas")) {
            report(o, "'msg.gas' was removed in Solidity 0.5.0, use 'gasleft()'", ReplaceTextFix(o, "gasleft()"))
          }
          "blockhash" -> if (o.parent is SolFunctionCallExpression && o.isGlobalMember("block", "blockhash")) {
            report(o, "'block.blockhash' was removed in Solidity 0.5.0, use 'blockhash'", ReplaceTextFix(o, "blockhash"))
          }
          "callcode" -> if (o.parent is SolFunctionCallExpression && o.expression.type is SolAddress && refersToBuiltinOnly(o)) {
            report(o.identifier!!, "'callcode' was removed in Solidity 0.5.0, use 'delegatecall'")
          }
          "balance" -> {
            val qualifier = (o.expression as? SolPrimaryExpression)?.varLiteral
            if (qualifier?.text == "this" && refersToBuiltinOnly(o)) {
              report(o, "Contract types have no address members since Solidity 0.5.0, use 'address(this).balance'",
                ReplaceTextFix(o, "address(this).balance"))
            }
          }
        }
      }

      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        val contract = o.parent as? SolContractDefinition ?: return
        val nameIdentifier = o.identifier
        if (nameIdentifier != null && nameIdentifier.text == contract.name) {
          report(nameIdentifier, "Functions can't have the same name as the contract since Solidity 0.5.0, use 'constructor(...)' to define a constructor")
          return
        }
        if (o.visibility == null) {
          report(nameIdentifier ?: o.firstChild, "Function visibility must be specified explicitly since Solidity 0.5.0")
        }
      }
    }
  }
}
