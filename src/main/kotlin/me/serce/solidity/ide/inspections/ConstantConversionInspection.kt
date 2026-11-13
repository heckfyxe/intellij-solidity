package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.*

/**
 * Reports number constants assigned to an integer variable that can't hold them, like solc:
 * `uint8 x = 300;`, `int8 y = -129;`, `uint z = 1 / 2;` (a fractional `rational_const`).
 * Only declarations with an explicit integer type and plain `=` assignments to an integer-typed target are checked.
 */
class ConstantConversionInspection : LocalInspectionTool() {
  override fun getDisplayName(): String = ""

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    fun check(targetType: SolType, value: SolExpression?) {
      if (value == null || targetType !is SolInteger || targetType.isLiteral) return
      // hex"..." is a string literal, it has its own conversion rules
      if (value is SolPrimaryExpression && value.hexLiteral != null) return
      val valueType = value.type
      val isConstant = valueType is SolRationalConstant || valueType is SolInteger && valueType.isLiteral
      if (!isConstant || targetType.isAssignableFrom(valueType)) return
      holder.registerProblem(
        value,
        "Type ${valueType.displayName()} is not implicitly convertible to expected type $targetType",
        ProblemHighlightType.GENERIC_ERROR
      )
    }

    return object : SolVisitor() {
      override fun visitVariableDefinition(o: SolVariableDefinition) {
        val typeName = o.variableDeclaration.typeName ?: return
        if (typeName.firstChild?.text == "var") return
        check(getSolType(typeName), o.expression)
      }

      override fun visitStateVariableDeclaration(o: SolStateVariableDeclaration) {
        check(getSolType(o.typeName), o.expression)
      }

      override fun visitConstantVariableDeclaration(o: SolConstantVariableDeclaration) {
        check(getSolType(o.typeName), o.expression)
      }

      override fun visitAssignmentExpression(o: SolAssignmentExpression) {
        val (target, value) = o.expressionList.takeIf { it.size == 2 } ?: return
        if (PsiTreeUtil.skipWhitespacesAndCommentsForward(target).elementType != SolidityTokenTypes.ASSIGN) return
        check(target.type, value)
      }
    }
  }
}
