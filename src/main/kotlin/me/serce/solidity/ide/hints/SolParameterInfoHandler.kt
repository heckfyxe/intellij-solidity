package me.serce.solidity.ide.hints

import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.ParameterInfoUtils
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
import me.serce.solidity.lang.types.findParent

class SolParameterInfoHandler : AbstractParameterInfoHandler<PsiElement, SolArgumentsDescription>() {

  override fun findTargetElement(file: PsiFile, offset: Int): PsiElement? {
    val element = file.findElementAt(offset) ?: return null

    element.findParent<SolFunctionCallExpression>()?.let { return it }
    // base constructor arguments: `contract B is A(…)` and `constructor() A(…)`, also modifier arguments
    element.findParent<SolInheritanceSpecifier>()?.takeIf { it.hasArgumentsAt(offset) }?.let { return it }
    element.findParent<SolModifierInvocation>()?.takeIf { it.hasArgumentsAt(offset) }?.let { return it }
    return null
  }

  /** Whether [offset] is between the parentheses that follow the name. */
  private fun PsiElement.hasArgumentsAt(offset: Int): Boolean {
    val lParen = node.findChildByType(LPAREN) ?: return false
    val rParen = node.findChildByType(RPAREN)
    return offset > lParen.startOffset && (rParen == null || offset <= rParen.startOffset)
  }

  override fun calculateParameterInfo(element: PsiElement): Array<SolArgumentsDescription>? =
    SolArgumentsDescription.findDescriptions(element).takeIf { it.isNotEmpty() }?.toTypedArray()

  override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
    if (context.parameterOwner != parameterOwner) {
      context.removeHint()
      return
    }
    val arguments = when (parameterOwner) {
      is SolInheritanceSpecifier -> parameterOwner.functionCallArguments
      is SolModifierInvocation -> parameterOwner.functionCallArguments
      is SolFunctionCallExpression -> parameterOwner.functionCallArguments
      else -> null
    }
    val idx = when {
      parameterOwner.startOffset == context.offset -> -1
      // `A()`: no arguments yet
      arguments == null -> 0
      else -> ParameterInfoUtils.getCurrentParameterIndex(arguments.node, context.offset, COMMA)
    }
    context.setCurrentParameter(idx)
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
        else -> emptyList()
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

    private fun createSolArgumentsDescriptionFromArguments(
      def: SolCallable, currentArguments: List<PsiElement>
    ): SolArgumentsDescription {
      return SolArgumentsDescription(def, currentArguments, argumentStrings(def))
    }

    private fun argumentStrings(def: SolCallable): Array<String> = argumentStrings(def.parseParameters())

    private fun argumentStrings(parameters: List<Pair<String?, SolType>>): Array<String> =
      parameters.map { (name, type) -> if (name == SolInternalTypeFactory.varargsId) varargsText(type) else "$type${name?.let { " $it" } ?: ""}" }.toTypedArray()
  }
}
