package me.serce.solidity.lang.resolve.ref

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.descendants
import me.serce.solidity.lang.completion.SolCompleter
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolFunctionDefMixin
import me.serce.solidity.lang.psi.impl.SolNewExpressionElement
import me.serce.solidity.lang.psi.impl.SolYulFunctionCallMixin
import me.serce.solidity.lang.psi.impl.SolYulPathElement
import me.serce.solidity.lang.resolve.SolYulResolver
import me.serce.solidity.lang.resolve.YulBuiltins
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.canBeApplied
import me.serce.solidity.lang.resolve.findBest
import me.serce.solidity.lang.resolve.function.SolFunctionResolver
import me.serce.solidity.lang.types.*
import me.serce.solidity.wrap

class SolUserDefinedTypeNameReference(element: SolUserDefinedTypeName) : SolReferenceBase<SolUserDefinedTypeName>(element), SolReference {
  override fun multiResolve(): Collection<PsiElement> {
    val parent = element.parent
    if (parent is SolNewExpressionElement) {
      return parent.reference.multiResolve()
    } else if (parent is SolUsingForElement) {
      val resolved = SolResolver.resolveUsingForElement(element)
      if (resolved != null) return listOf(resolved)
    }
    return SolResolver.resolveTypeNameUsingImports(element)
  }

  override fun getVariants() = SolCompleter.completeTypeName(element)
}

class SolQualifierTypeNameReference(
  element: SolUserDefinedTypeName, private val identifier: PsiElement
) : SolReferenceBase<SolUserDefinedTypeName>(element), SolReference {

  override fun calculateDefaultRangeInElement(): TextRange {
    return TextRange(identifier.startOffsetInParent, identifier.startOffsetInParent + identifier.textLength)
  }

  override fun multiResolve(): Collection<PsiElement> {
    return SolResolver.resolveTypeNameUsingImports(identifier)
  }

  override fun handleElementRename(newName: String): PsiElement {
    doRename(identifier, newName)
    return element
  }
}

class SolVarLiteralReference(element: SolVarLiteral) : SolReferenceBase<SolVarLiteral>(element), SolReference {
  override fun multiResolve() = SolResolver.resolveVarLiteralReference(element)

  override fun getVariants() = SolCompleter.completeLiteral(element).toList().toTypedArray()
}

/** The first identifier of a Yul path (`x` in `x.slot`); completion comes from `SolYulCompletionContributor`. */
class SolYulPathReference(element: SolYulPathElement) : SolReferenceBase<SolYulPathElement>(element), SolReference {
  override fun calculateDefaultRangeInElement(): TextRange = element.referenceNameElement.parentRelativeRange

  override fun multiResolve(): Collection<PsiElement> = SolYulResolver.resolveVariable(element)
}

/** The name of a called Yul function; built-ins resolve to nothing (see [YulBuiltins]). */
class SolYulFunctionCallReference(element: SolYulFunctionCallMixin) : SolReferenceBase<SolYulFunctionCallMixin>(element), SolReference {
  override fun calculateDefaultRangeInElement(): TextRange = element.referenceNameElement.parentRelativeRange

  override fun multiResolve(): Collection<PsiElement> = SolYulResolver.resolveFunction(element)
}


class SolModifierReference(
  element: SolReferenceElement,
  private val modifierElement: SolModifierInvocationElement
) : SolReferenceBase<SolReferenceElement>(element), SolReference {

  override fun calculateDefaultRangeInElement() = modifierElement.referenceNameElement.rangeRelativeTo(element)

  override fun multiResolve(): List<SolNamedElement> {
    val contract = modifierElement.findContract() ?: return emptyList()
    val superNames = (contract.collectSupers.map { it.name } + contract.name).filterNotNullTo(hashSetOf())
    return SolResolver.resolveTypeNameUsingImports(modifierElement)
      .filterIsInstance<SolModifierDefinition>()
      .filter { it.contract.name in superNames }
  }

  override fun getVariants() = SolCompleter.completeModifier(modifierElement)

  override fun doRename(identifier: PsiElement, newName: String) {
    val modifierId = modifierElement.descendants().filter { it.elementType == SolidityTokenTypes.IDENTIFIER }.firstOrNull() ?: return
    super.doRename(modifierId, newName)
  }
}

class SolMemberAccessReference(element: SolMemberAccessExpression) : SolReferenceBase<SolMemberAccessExpression>(element), SolReference {
  override fun calculateDefaultRangeInElement(): TextRange {
    return element.identifier?.parentRelativeRange ?: super.calculateDefaultRangeInElement()
  }

  override fun multiResolve(): List<SolNamedElement> {
    val firstMemberElement = element.childOfType<SolPrimaryExpression>()
    val importAlias = firstMemberElement?.varLiteral?.let { varLiteral -> SolResolver.resolveAlias(varLiteral) }
    if (importAlias != null && SolResolver.isAliasOfFile(importAlias)) {
      return when (element.parent is SolFunctionCallExpression) {
        true -> (element.parent.reference as SolFunctionCallReference).resolveFunctionCallAndFilter()
          .mapNotNull { it.resolveElement() }

        else -> {
          SolResolver.resolveTypeNameUsingImports(element).toList()
        }
      }
    }
    return SolResolver.resolveMemberAccess(element).mapNotNull { it.resolveElement() }
  }

  override fun getVariants() = SolCompleter.completeMemberAccess(element)
}

class SolNewExpressionReference(val element: SolNewExpression) : SolReferenceBase<SolNewExpression>(element), SolReference {

  override fun calculateDefaultRangeInElement(): TextRange {
    return element.referenceNameElement.parentRelativeRange
  }

  override fun multiResolve(): Collection<PsiElement> {
    val types = SolResolver.resolveTypeNameUsingImports(element.referenceNameElement)
    return types
      .filterIsInstance<SolContractDefinition>()
      .flatMap { contract -> contract.findConstructors().ifEmpty { listOf(contract) } }
  }
}

fun SolContractDefinition.findConstructors(): List<SolElement> {
  return if (this.constructorDefinitionList.isNotEmpty()) {
    this.constructorDefinitionList
  } else {
    this.functionDefinitionList
      .filter { it.name == this.name }
  }
}

class SolFunctionCallReference(element: SolFunctionCallExpression) : SolReferenceBase<SolFunctionCallExpression>(element), SolReference {
  override fun calculateDefaultRangeInElement(): TextRange {
    return element.referenceNameElement.parentRelativeRange
  }

  fun resolveFunctionCall(): Collection<SolCallable> {
    if (element.parent is SolRevertStatement || isRequireErrorArgument()) {
      val errors = SolResolver.resolveTypeNameUsingImports(element).filterIsInstance<SolErrorDefinition>()
      if (errors.isNotEmpty()) {
        return errors
      }
    }
    if (element.parent is SolEmitStatement) {
      val events = SolResolver.resolveTypeNameUsingImports(element).filterIsInstance<SolEventDefinition>()
      if (events.isNotEmpty()) {
        return events
      }
    }
    if (element.firstChild is SolPrimaryExpression) {
      val structs = SolResolver.resolveTypeNameUsingImports(element.firstChild).filterIsInstance<SolStructDefinition>()
      if (structs.isNotEmpty()) {
        return structs
      }
    }
    val resolved: Collection<SolCallable> = when (val expr = element.expression) {
      is SolPrimaryExpression -> {
        val regular = expr.varLiteral?.let { SolResolver.resolveVarLiteral(it) }
          ?.filter { it !is SolStateVariableDeclaration }
          ?.filterIsInstance<SolCallable>()
          ?: emptyList()
        val casts = resolveElementaryTypeCasts(expr)
        regular + casts
      }
      is SolMemberAccessExpression -> {
        SolResolver.resolveMemberFunctions(expr)
      }
      else ->
        emptyList()
    }
    return removeOverrides(resolved.findBest { it.callablePriority })
  }

  private fun isRequireErrorArgument(): Boolean {
    val arguments = element.parent as? SolFunctionCallArguments ?: return false
    val invocation = arguments.parent as? SolFunctionInvocation ?: return false
    val requireCall = invocation.parent as? SolFunctionCallExpression ?: return false
    return requireCall.functionCallArguments.expressionList.getOrNull(1) == element &&
      (requireCall.expression as? SolPrimaryExpression)?.varLiteral?.name == "require"
  }

  private fun removeOverrides(callables: Collection<SolCallable>): Collection<SolCallable> {
    val overridden = callables.filterIsInstance<SolFunctionDefinition>()
      .flatMapTo(hashSetOf()) { SolFunctionResolver.collectOverridden(it) }
    return callables.filter { it !is SolFunctionDefinition || it !in overridden }
  }

  private fun resolveElementaryTypeCasts(expr: SolPrimaryExpression): Collection<SolCallable> {
    return expr.elementaryTypeName
      ?.let {
        val type = getSolType(it)
        object : SolCallable {
          override fun resolveElement(): SolNamedElement? = null
          override fun parseParameters(): List<Pair<String?, SolType>> = listOf(null to SolUnknown)
          override fun parseType(): SolType = type
          override val callablePriority: Int = 1000
          override fun getName(): String? = null
        }
      }
      .wrap()
  }

  override fun multiResolve(): Collection<PsiElement> {
    return resolveFunctionCallAndFilter()
      .mapNotNull { it.resolveElement() }
  }

  fun resolveFunctionCallAndFilter(): List<SolCallable> {
    return resolveFunctionCall()
      .filter { it.canBeApplied(element.functionCallArguments) }
  }
}

class LibraryFunDefinition(private val original: SolFunctionDefinition) : SolFunctionDefinition by original {
  override val parameters: List<SolParameterDef>
    get() = original.parameters.drop(1)


  override fun parseParameters(): List<Pair<String?, SolType>> {
    return SolFunctionDefMixin.parseParameters(parameters)
  }

  override fun equals(other: Any?): Boolean {
    if (other is LibraryFunDefinition) {
      return original == other.original
    }
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return original.hashCode()
  }

}
fun SolFunctionDefinition.toLibraryFunDefinition(): SolFunctionDefinition {
  return LibraryFunDefinition(this)
}
