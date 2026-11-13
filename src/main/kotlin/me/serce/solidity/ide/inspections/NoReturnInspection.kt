package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.types.SolInternalTypeFactory
import me.serce.solidity.lang.types.findContract

class NoReturnInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        o.inspectReturns(holder)
      }
    }
  }
}

private fun SolFunctionDefinition.inspectReturns(holder: ProblemsHolder) {
  val block = block ?: return
  val returns = returns ?: return
  if (block.returns) return
  val returnParams = returns.parameterDefList
  if (returnParams.any { it.name == null }) {
    holder.registerProblem(this, "No return statement")
    return
  }
  returnParams.filterNot { block.hasAssignment(it) }.forEach {
    holder.registerProblem(it, "Return variable not assigned")
  }
}

private val SolFunctionCallExpression.revert: Boolean
  get() = name == "revert" && reference?.resolve()?.isGlobal() == true

private fun SolStatement.hasAssignment(el: SolNamedElement): Boolean {
  if (throwSt != null || revertStatement != null) return true
  variableDefinition?.let { return it.hasAssignment(el) }
  expression?.let { return it.hasAssignment(el) }
  inlineAssemblyStatement?.yulBlock?.let { return it.hasAssignment(el) }
  block?.let { return it.hasAssignment(el) }
  ifStatement?.let { return it.hasAssignment(el) }
  tryStatement?.let { return it.hasAssignment(el) }
  tupleStatement?.let { st ->
    val declaration = st.variableDeclaration
    if (declaration != null) {
      return declaration.declarationList?.declarationItemList?.any { it.identifier?.text == el.name }
        ?: declaration.typedDeclarationList?.typedDeclarationItemList?.any { it.identifier?.text == el.name }
        ?: false
    }
    (st.expressionList.firstOrNull() as? SolSeqExpression)?.let { seq ->
      return seq.expressionList.any { it.isReferenceTo(el) }
    }
  }
  return false
}

private fun SolExpression.isReferenceTo(el: SolNamedElement): Boolean =
  (this as? SolPrimaryExpression)?.varLiteral?.reference?.resolve() == el

private val SolStatement.returns: Boolean
  get() {
    (expression as? SolFunctionCallExpression)?.let { if (it.revert) return true }
    if (revertStatement != null) return true
    inlineAssemblyStatement?.yulBlock?.let { return it.returns }
    if (throwSt != null) return true
    block?.let { return it.returns }
    ifStatement?.let { return it.returns }
    tryStatement?.let { return it.returns }
    return returnSt != null || returnTupleStatement != null
  }

// the Yul builtins that end execution; `returndatasize()` and friends only share the prefix
private val YUL_TERMINATORS = setOf("return", "revert", "stop", "invalid", "selfdestruct")

private val SolYulStatement.returns: Boolean
  get() = yulFunctionCall?.text?.substringBefore('(')?.trim() in YUL_TERMINATORS

private val SolYulBlock.returns: Boolean
  get() = yulStatementList.any { it.returns }

private val SolBlock.returns: Boolean
  get() = (statementList + uncheckedBlockList.flatMap { it.statementList }).any { it.returns }

private fun SolBlock.hasAssignment(el: SolNamedElement): Boolean =
  statementList.any { it.hasAssignment(el) } || uncheckedBlockList.any { block -> block.statementList.any { it.hasAssignment(el) } }

// an `if` without `else` never guarantees the assignment or the return
private val SolIfStatement.returns: Boolean
  get() = statementList.size != 1 && statementList.all { it.returns }

private fun SolIfStatement.hasAssignment(el: SolNamedElement): Boolean =
  statementList.size != 1 && statementList.all { it.hasAssignment(el) }

private fun SolVariableDefinition.hasAssignment(el: SolNamedElement): Boolean =
  variableDeclaration.reference?.resolve() == el

// an assignment anywhere in the assembly block counts: Yul control flow isn't analysed
private fun SolYulBlock.hasAssignment(el: SolNamedElement): Boolean =
  PsiTreeUtil.findChildrenOfType(this, SolYulAssignment::class.java).any { assignment ->
    assignment.children.any { it is SolYulPath && it.reference?.resolve() == el } // also legacy `=: x`
  }

private fun SolExpression.hasAssignment(el: SolNamedElement): Boolean {
  var hasAssignment = false
  accept(object : PsiRecursiveElementWalkingVisitor() {
    override fun visitElement(element: PsiElement) {
      hasAssignment = element is SolAssignmentExpression && element.expressionList.any { it.isReferenceTo(el) } ||
        element is SolFunctionCallExpression && element.revert
      if (hasAssignment) {
        stopWalking()
        return
      }
      super.visitElement(element)
    }
  })
  return hasAssignment
}

private fun SolTryStatement.hasAssignment(el: SolNamedElement): Boolean =
  block?.hasAssignment(el) == true && catchClauseList.all { it.block?.hasAssignment(el) == true }

private val SolTryStatement.returns: Boolean
  get() = block?.returns == true && catchClauseList.all { it.block?.returns == true }

private fun SolElement.isGlobal(): Boolean =
  findContract() == SolInternalTypeFactory.of(project).globalType.ref
