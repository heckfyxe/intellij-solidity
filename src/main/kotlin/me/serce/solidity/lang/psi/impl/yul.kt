package me.serce.solidity.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.ref.SolYulFunctionCallReference

/** A Yul variable declared by `let`, or a parameter or return variable of a Yul function. */
abstract class SolYulVariableMixin(node: ASTNode) : SolNamedElementImpl(node), SolYulVariable {
  /** The Yul function this is a parameter or return variable of, null for a `let` variable. */
  val function: SolYulFunctionDefinition?
    get() = parent as? SolYulFunctionDefinition

  override fun getIcon(flags: Int) = SolidityIcons.STATE_VAR
}

abstract class SolYulFunctionDefMixin(node: ASTNode) : SolNamedElementImpl(node), SolYulFunctionDefinition {
  private val arrowOffset: Int
    get() = node.getChildren(null).firstOrNull { it.text == "->" }?.startOffset ?: Int.MAX_VALUE

  val parameters: List<SolYulVariable>
    get() = arrowOffset.let { arrow -> yulVariableList.filter { it.textOffset < arrow } }

  val returnVariables: List<SolYulVariable>
    get() = arrowOffset.let { arrow -> yulVariableList.filter { it.textOffset > arrow } }

  override fun getIcon(flags: Int) = SolidityIcons.FUNCTION
}

abstract class SolYulFunctionCallMixin(node: ASTNode) : SolNamedElementImpl(node), SolYulFunctionCall, SolReferenceElement {
  // built-ins such as `return` and `address` are keywords, not identifiers
  override val referenceNameElement: PsiElement
    get() = firstChild
  override val referenceName: String
    get() = referenceNameElement.text

  override fun getNameIdentifier(): PsiElement? = referenceNameElement

  override fun getName(): String = referenceName

  override fun getReference() = SolYulFunctionCallReference(this)
}
