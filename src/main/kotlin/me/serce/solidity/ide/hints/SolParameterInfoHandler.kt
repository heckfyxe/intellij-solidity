package me.serce.solidity.ide.hints

import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.ParameterInfoUtils
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.childrenOfType
import com.intellij.psi.util.endOffset
import me.serce.solidity.lang.core.SolidityTokenTypes.*
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolFunctionDefMixin
import me.serce.solidity.lang.resolve.ref.findConstructors
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.canBeApplied
import me.serce.solidity.lang.resolve.ref.SolFunctionCallReference
import me.serce.solidity.lang.types.SolInternalTypeFactory
import me.serce.solidity.lang.types.SolType
import me.serce.solidity.lang.types.SolUnknown
import me.serce.solidity.lang.types.findParentOrNull

class SolParameterInfoHandler : AbstractParameterInfoHandler<PsiElement, SolArgumentsDescription>() {

  override fun findTargetElement(file: PsiFile, offset: Int): PsiElement? {
    val element = file.findElementAt(offset) ?: return null

    element.findParentOrNull<SolFunctionCallExpression>()?.let { return it }
    // base constructor arguments: `contract B is A(…)` and `constructor() A(…)`, also modifier arguments
    element.findParentOrNull<SolInheritanceSpecifier>()?.takeIf { it.hasArgumentsAt(offset) }?.let { return it }
    element.findParentOrNull<SolModifierInvocation>()?.takeIf { it.hasArgumentsAt(offset) }?.let { return it }

    if (element.parent.prevSibling is SolAssignmentExpression) {
      val primaryExpressionChildren = element.parent.prevSibling.childrenOfType<SolPrimaryExpression>()
      if (primaryExpressionChildren.isNotEmpty()) {
        return primaryExpressionChildren.last()
      }
    }

    var currentElement = if (element.prevSibling == null) {
      element.parent
    } else {
      element
    }
    var lParenFound = false
    while (currentElement.prevSibling != null) {
      currentElement = currentElement.prevSibling
      if (!lParenFound && currentElement.isToken(LPAREN)) {
        lParenFound = true
      } else if (lParenFound && currentElement.text != null && currentElement.text.isNotBlank()) {
        return if (currentElement is SolEmitStatement || currentElement is SolRevertStatement) {
          currentElement.childrenOfType<SolPrimaryExpression>().firstOrNull() ?: currentElement
        } else {
          currentElement
        }
      }
    }
    return null
  }

  private fun PsiElement.isToken(type: IElementType) = node?.elementType == type

  /** Whether [offset] is between the parentheses that follow the name. */
  private fun PsiElement.hasArgumentsAt(offset: Int): Boolean {
    val lParen = node.findChildByType(LPAREN) ?: return false
    val rParen = node.findChildByType(RPAREN)
    return offset > lParen.startOffset && (rParen == null || offset <= rParen.startOffset)
  }

  override fun calculateParameterInfo(element: PsiElement): Array<SolArgumentsDescription>? {
    if (element is SolInheritanceSpecifier || element is SolModifierInvocation) {
      return SolArgumentsDescription.findDescriptions(element).takeIf { it.isNotEmpty() }?.toTypedArray()
    }
    val callElement: PsiElement = element.childrenOfType<SolFunctionCallExpression>().firstOrNull() ?: element
    val descriptions = SolArgumentsDescription.findDescriptions(callElement)
    return descriptions.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
    if (context.parameterOwner != parameterOwner) {
      context.removeHint()
      return
    }
    val idx = when {
      parameterOwner.startOffset == context.offset -> -1
      parameterOwner is SolInheritanceSpecifier -> countCommasBefore(parameterOwner.functionCallArguments, context.offset)
      parameterOwner is SolModifierInvocation -> countCommasBefore(parameterOwner.functionCallArguments, context.offset)
      parameterOwner is SolFunctionCallExpression -> ParameterInfoUtils.getCurrentParameterIndex(
        parameterOwner.functionCallArguments.node, context.offset, COMMA
      )

      parameterOwner.parent?.nextSibling is SolSeqExpression -> ParameterInfoUtils.getCurrentParameterIndex(
        parameterOwner.parent.nextSibling.node, context.offset, COMMA
      )

      else -> computeIndexByScanning(parameterOwner, context.offset)
    }
    context.setCurrentParameter(idx)
  }

  private fun countCommasBefore(arguments: PsiElement?, offset: Int): Int =
    generateSequence(arguments?.firstChild) { it.nextSibling }.count { it.isToken(COMMA) && it.startOffset < offset }

  private fun computeIndexByScanning(anchor: PsiElement, targetOffset: Int): Int {
    var index = -1
    var currentElement =
      if (anchor.parent is SolEmitStatement || anchor.parent is SolRevertStatement) anchor.parent else anchor
    var offset = anchor.startOffset

    while (offset < targetOffset && currentElement != null) {
      when {
        index == -1 && currentElement.isToken(LPAREN) -> index = 0
        currentElement.isToken(COMMA) -> index++
      }
      offset = currentElement.endOffset
      currentElement = currentElement.nextSibling
    }
    return index
  }

  override fun updateUI(p: SolArgumentsDescription, context: ParameterInfoUIContext) {
    val range = p.getArgumentRange(context.currentParameterIndex)
    context.setupUIComponentPresentation(
      p.presentText,
      range.startOffset,
      range.endOffset,
      !context.isUIComponentEnabled,
      false,
      false,
      if (p.valid) context.defaultParameterColor.brighter() else context.defaultParameterColor)
  }
}

/** How a parameter taking any number of further arguments is shown: `...` for any type (`abi.encode`), `string...`. */
fun varargsText(type: SolType): String = if (type == SolUnknown) "..." else "$type..."

class SolArgumentsDescription(
  callable: SolCallable?, callArguments: List<PsiElement>, val arguments: Array<String>
) {

  val valid = if (callable != null && callArguments.isNotEmpty() && callArguments.first().parent is SolFunctionCallArguments) {
    callable.canBeApplied(callArguments.first().parent as SolFunctionCallArguments)
  } else {
    false
  }
  val presentText = if (arguments.isEmpty()) "<no parameters>" else arguments.joinToString(", ")

  fun getArgumentRange(argumentIndex: Int): TextRange {
    // every argument past the last parameter goes to a trailing `...`
    val index = if (argumentIndex >= arguments.size && arguments.lastOrNull()?.endsWith("...") == true) arguments.size - 1 else argumentIndex
    if (index < 0 || index >= arguments.size) {
      return TextRange.EMPTY_RANGE
    }
    val start = arguments.take(index).sumOf { it.length + 2 }
    return TextRange(start, start + arguments[index].length)
  }

  companion object {

    fun findDescriptions(call: PsiElement): List<SolArgumentsDescription> {
      val ref = call.reference
      return when {
        call is SolFunctionCallExpression && ref is SolFunctionCallReference -> ref.resolveFunctionCall().map { def ->
          createSolArgumentsDescriptionFromArguments(def, call.functionCallArguments.expressionList)
        }

        call is SolInheritanceSpecifier -> baseConstructors(call.userDefinedTypeName.reference?.multiResolve().orEmpty(), call.functionCallArguments?.expressionList.orEmpty())
        call is SolModifierInvocation -> resolveModifierInvocation(call)
        call.parent is SolAssignmentExpression -> resolveStructAssignment(call)
        call is SolMemberAccessExpression -> resolveMemberAccess(call)
        else -> resolveByPsiElement(call)
      }
    }

    /** The constructors of the contracts among [targets]; a contract without one takes no arguments. */
    private fun baseConstructors(targets: Collection<PsiElement>, arguments: List<PsiElement>): List<SolArgumentsDescription> =
      targets.filterIsInstance<SolContractDefinition>().flatMap { contract ->
        val constructors = contract.findConstructors().filterIsInstance<SolCallable>()
        if (constructors.isEmpty()) listOf(SolArgumentsDescription(null, arguments, emptyArray()))
        else constructors.map { createSolArgumentsDescriptionFromArguments(it, arguments) }
      }

    /** A modifier, or in a constructor header, the constructor of a base contract. */
    private fun resolveModifierInvocation(call: SolModifierInvocation): List<SolArgumentsDescription> {
      val arguments = call.functionCallArguments?.expressionList.orEmpty()
      val modifiers = call.reference?.multiResolve().orEmpty().filterIsInstance<SolModifierDefinition>()
      if (modifiers.isNotEmpty()) return modifiers.map { modifier ->
        SolArgumentsDescription(null, arguments, argumentStrings(SolFunctionDefMixin.parseParameters(modifier.parameterList?.parameterDefList.orEmpty())))
      }
      val contracts = SolResolver.resolveTypeNameUsingImports(call.varLiteral).filter { it.name == call.varLiteral.text }
      return baseConstructors(contracts, arguments)
    }

    private fun resolveStructAssignment(call: PsiElement): List<SolArgumentsDescription> {
      val currentArguments: List<PsiElement> = getArgumentsFromPsiElement(call)
      return SolResolver.resolveTypeNameUsingImports(call)
        .filter { it.name == call.text }
        .filterIsInstance<SolStructDefinition>()
        .map { def ->
          createSolArgumentsDescriptionFromArguments(def, currentArguments)
        }
    }

    private fun resolveMemberAccess(call: SolMemberAccessExpression): List<SolArgumentsDescription> {
      val currentArguments: List<PsiElement> = getArgumentsFromPsiElement(call)
      return SolResolver.resolveMemberFunctions(call).map { def ->
        createSolArgumentsDescriptionFromArguments(def, currentArguments)
      }
    }

    private fun resolveByPsiElement(call: PsiElement): List<SolArgumentsDescription> {
      val currentArguments: List<PsiElement> = getArgumentsFromPsiElement(call)
      val elementsFromSearch = SolResolver.lexicalDeclarations(call)
        .filter { it.name == call.text }
        .filterIsInstance<SolCallable>()
        .ifEmpty {
          SolResolver.resolveTypeNameUsingImportsWithFunctions(call)
            .asSequence()
            .filter { it.name == call.text }
            .filterIsInstance<SolCallable>()
        }
        .toList()

      return elementsFromSearch.map { def ->
        var parameters = argumentStrings(def)
        if (call.prevSibling?.node?.elementType == DOT) {
          val functionCallMemberElement = SolResolver.lexicalDeclarations(call.prevSibling.prevSibling)
            .firstOrNull { it.name == call.prevSibling.prevSibling.text }
          if (parameters.isNotEmpty() && (functionCallMemberElement is SolElementaryTypeName || (functionCallMemberElement?.firstChild is SolElementaryTypeName))) {
            parameters = parameters.drop(1).toTypedArray()
          }
        }
        SolArgumentsDescription(def, currentArguments, parameters)
      }
    }

    private fun createSolArgumentsDescriptionFromArguments(
      def: SolCallable, currentArguments: List<PsiElement>
    ): SolArgumentsDescription {
      return SolArgumentsDescription(def, currentArguments, argumentStrings(def))
    }

    private fun argumentStrings(def: SolCallable): Array<String> = argumentStrings(def.parseParameters())

    private fun argumentStrings(parameters: List<Pair<String?, SolType>>): Array<String> =
      parameters.map { (name, type) -> if (name == SolInternalTypeFactory.varargsId) varargsText(type) else "$type${name?.let { " $it" } ?: ""}" }.toTypedArray()

    fun getArgumentsFromPsiElement(element: PsiElement): List<PsiElement> {
      (element.parent?.nextSibling as? SolSeqExpression)?.let { return it.expressionList }

      val arguments = mutableListOf<PsiElement>()
      var currentElement: PsiElement? = element.nextSibling
      var depth = 0
      while (currentElement != null) {
        val type = currentElement.node?.elementType
        when (type) {
          LPAREN -> depth++
          RPAREN -> if (depth == 0) break else depth--
          COMMA -> { /* separator, ignore */ }
          else -> if (!currentElement.text.isNullOrBlank()) arguments.add(currentElement)
        }
        currentElement = currentElement.nextSibling
      }
      return arguments
    }
  }
}
