package me.serce.solidity.lang.types

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import me.serce.solidity.firstOrElse
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.core.solidityVersionRange
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolMemberAccessElement
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.canBeApplied
import me.serce.solidity.lang.resolve.isExternallyReferenceable
import me.serce.solidity.lang.resolve.ref.SolFunctionCallReference
import me.serce.solidity.lang.resolve.ref.toLibraryFunDefinition
import me.serce.solidity.lang.types.SolArray.SolDynamicArray
import me.serce.solidity.lang.types.SolArray.SolStaticArray
import java.math.BigInteger

fun getSolType(type: SolTypeName?): SolType {
  return when (type) {
    is SolBytesArrayTypeName -> {
      val text = type.bytesNumType.text
      if (text == "bytes") SolBytes else SolFixedBytes.parse(text)
    }
    is SolElementaryTypeName -> {
      when (val text = type.firstChild.text) {
        "bool" -> SolBoolean
        "string" -> SolString(text.length)
        "address" -> if (type.childrenOfType<LeafPsiElement>().drop(1).any { it.elementType == SolidityTokenTypes.PAYABLE }) SolAddress.PAYABLE else SolAddress.NON_PAYABLE
        "payable" -> SolAddress.PAYABLE
        "bytes" -> SolBytes
        "byte" -> SolFixedBytes(1)
        else -> try {
            when {
              text.matches(SolFixedByte.regex) -> SolFixedByte.parse(text)
              text.matches(SolFixedBytes.regex) -> SolFixedBytes.parse(text)
              else -> SolInteger.parse(text)
            }
          } catch (e: IllegalArgumentException) {
            SolUnknown
          }
      }
    }
    is SolUserDefinedLocationTypeName ->
      type.userDefinedTypeName?.let { getSolTypeFromUserDefinedTypeName(it) } ?: SolUnknown
    is SolUserDefinedTypeName -> getSolTypeFromUserDefinedTypeName(type)
    is SolMappingTypeName -> when {
      type.typeNameList.size >= 2 -> SolMapping(
        getSolType(type.typeNameList[0]),
        getSolType(type.typeNameList[1])
      )
      else -> SolUnknown
    }
    is SolArrayTypeName -> {
      val sizeExpr = type.expression
      when {
        sizeExpr == null -> SolDynamicArray(getSolType(type.typeName))
        sizeExpr is SolPrimaryExpression ->
          tryParseStaticArraySize(sizeExpr)?.let { SolStaticArray(getSolType(type.typeName), it) } ?: SolUnknown
        else -> SolUnknown
      }
    }
    is SolFunctionTypeName -> SolFunctionTypeType(type)
    else -> SolUnknown
  }
}

private fun tryParseStaticArraySize(sizeExpr: SolPrimaryExpression): Int? {
  val numberLiteral = sizeExpr.numberLiteral ?: return null
  return (numberLiteral.decimalNumber ?: numberLiteral.hexNumber)?.text?.tryParseIntLiteral()
}

private fun String.tryParseIntLiteral(): Int? {
  val normalized = this.filterNot { it == '_' }
  return when {
    normalized.startsWith("0x", ignoreCase = true) -> normalized.drop(2).toIntOrNull(16)
    else -> normalized.toIntOrNull()
  }
}

private fun getSolTypeFromUserDefinedTypeName(type: SolUserDefinedTypeName): SolType {
  val name = type.name
  if (name != null && isInternal(name)) {
    val internalType = SolInternalTypeFactory.of(type.project).byName(name)
    return internalType ?: SolUnknown
  }
  val resolvedTypes = SolResolver.resolveTypeNameUsingImports(type)
  return resolvedTypes.asSequence()
    .map {
      when (it) {
        is SolContractDefinition -> SolContract(it)
        is SolStructDefinition -> SolStruct(it)
        is SolEnumDefinition -> SolEnum(it)
        is SolUserDefinedValueTypeDefinition -> SolUserDefinedValueTypeType(it)
        is SolImportAlias -> (it.parent as? SolImportAliasedPair)?.let { getSolTypeFromUserDefinedTypeName(it.userDefinedTypeName) }
        else -> null
      }
    }
    .filterNotNull()
    .firstOrElse(SolUnknown)
}

fun inferDeclType(decl: SolNamedElement): SolType {
  return when (decl) {
    is SolDeclarationItem -> {
      val list = decl.findParent<SolDeclarationList>() ?: decl.findParent<SolTypedDeclarationList>() ?: return SolUnknown
      val def = list.findParent<SolVariableDefinition>() ?: return SolUnknown
      val inferred = inferExprType(def.expression)
      val declarationItemList = (list as? SolDeclarationList)?.declarationItemList
        ?: (list as? SolTypedDeclarationList)?.typedDeclarationItemList ?: return SolUnknown
      val declIndex = declarationItemList.indexOf(decl)
      (inferred as? SolTuple)?.types?.getOrNull(declIndex) ?: SolUnknown
    }
    is SolTypedDeclarationItem -> getSolType(decl.typeName)
    is SolVariableDeclaration -> {
      return if (decl.typeName == null || decl.typeName?.firstChild?.text == "var") {
        when (val parent = decl.parent) {
          is SolVariableDefinition -> inferExprType(parent.expression)
          else -> SolUnknown
        }
      } else getSolType(decl.typeName)
    }
    is SolContractDefinition -> SolContract(decl)
    is SolStructDefinition -> SolStruct(decl)
    is SolEnumDefinition -> SolEnum(decl)
    is SolEnumValue -> inferDeclType(decl.parent as SolNamedElement)
    is SolParameterDef -> getSolType(decl.typeName)
    is SolStateVariableDeclaration -> getSolType(decl.typeName)
    is SolFunctionDefinition -> SolFunctionType(decl)
    is SolErrorDefinition -> SolErrorReferenceType(decl)
    is SolEventDefinition -> SolEventReferenceType(decl)
    is SolUserDefinedValueTypeDefinition -> SolUserDefinedValueTypeReferenceType(decl)
    else -> SolUnknown
  }
}

fun inferRefType(ref: SolVarLiteral): SolType {
  return when (ref.name) {
    "this" -> {
      ref.findContract()
        ?.let { SolContract(it) } ?: SolUnknown
    }
    "super" -> SolUnknown
    else -> {
      val declarations = SolResolver.resolveVarLiteral(ref)
      return declarations.asSequence()
        .map { inferDeclType(it) }
        .filter { it != SolUnknown }
        .firstOrElse(SolUnknown)
    }
  }
}

inline fun <reified T : PsiElement> PsiElement.findParent(): T? {
  return this.ancestors
    .filterIsInstance<T>()
    .firstOrNull()
}

inline fun <reified T : PsiElement> PsiElement.findParentOrNull(): T? = findParent()

fun PsiElement.findContract(): SolContractDefinition? = this.findParentOrNull()

fun SolNamedElement.outerContract() = parent?.takeIf { it is SolContractDefinition || it is SolEnumDefinition || it is SolStructDefinition }?.findContract()

fun PsiElement.isBuiltin() = this.containingFile.virtualFile == null

fun inferExprType(expr: SolExpression?): SolType {
  return when (expr) {
    is SolPrimaryExpression -> {
      expr.varLiteral?.let { inferRefType(it) }
        ?: expr.booleanLiteral?.let { SolBoolean }
        ?: expr.stringLiteral?.let { SolString.inferType(it) }
        ?: expr.numberLiteral?.let { SolInteger.inferType(it) }
        ?: expr.hexLiteral?.let { SolInteger.inferType(it) }
        ?: expr.elementaryTypeName?.let { getSolType(it) }
        ?: SolUnknown
    }
    is SolPlusMinExpression,
    is SolMultDivExpression,
    is SolExponentExpression,
    is SolShiftExpression -> {
      val operands = PsiTreeUtil.getChildrenOfTypeAsList(expr, SolExpression::class.java)
      getNumericExpressionType(
        operands.firstOrNull()?.let { PsiTreeUtil.skipWhitespacesAndCommentsForward(it) }?.text,
        inferExprType(operands.firstOrNull()),
        inferExprType(operands.secondOrNull())
      )
    }
    is SolFunctionCallExpression -> {
      val candidates = (expr.reference as SolFunctionCallReference).resolveFunctionCall()
      // abi.decode's builtin signature can't describe its type list argument
      if (candidates.any { it.isAbiDecode() }) {
        inferAbiDecodeType(expr)
      } else {
        candidates.firstOrNull { it.canBeApplied(expr.functionCallArguments) }?.parseType() ?: SolUnknown
      }
    }
    is SolAndExpression,
    is SolOrExpression,
    is SolEqExpression,
    is SolCompExpression -> SolBoolean
    is SolTernaryExpression -> inferExprType(expr.expressionList.secondOrNull())
    is SolIndexAccessExpression -> {
      when (val arrType = inferExprType(expr.expressionList.firstOrNull())) {
        is SolArray -> arrType.type
        is SolMapping -> arrType.to
        is SolFixedBytes -> SolFixedBytes(1)
        else -> SolUnknown
      }
    }
    is SolMemberAccessExpression -> {
      val member = SolResolver.resolveMemberAccess(expr).firstOrNull() ?: return SolUnknown
      // `C.MyError` / `I.Transfer` not being called refer to the error/event itself, e.g. in `I.Transfer.selector`
      val constructed = (member as? SolMemberConstructor<*>)?.ref?.takeIf { expr.parent !is SolFunctionCallExpression }
      when (constructed) {
        is SolErrorDefinition -> SolErrorReferenceType(constructed)
        is SolEventDefinition -> SolEventReferenceType(constructed)
        else -> member.parseType()
      }
    }
    is SolSeqExpression -> when {
      expr.expressionList.isEmpty() -> SolUnknown
      else -> inferExprType(expr.expressionList.firstOrNull())
    }
    is SolUnaryExpression -> {
      val operandType = inferExprType(expr.expression)
      val constant = operandType.constantValue
      when (expr.firstChild?.text) {
        "!" -> SolBoolean
        "-" -> if (constant != null) SolRationalConstant.of(-constant) else operandType
        "~" -> when {
          constant == null -> operandType
          constant.isInteger -> SolInteger.ofConstant(constant.numerator.not())
          else -> SolUnknown
        }
        else -> operandType
      }
    }
    is SolMetaTypeExpression -> SolMetaType(
      getSolType(expr.typeName),
      enumMinMax = expr.containingFile?.solidityVersionRange()?.isBelow("0.8.8") != true
    )
    else -> SolUnknown
  }
}

private fun <E> List<E>.secondOrNull(): E? {
  return if (size < 2) null else this[1]
}

/** Results of constant folding beyond this many bits are not tracked (solc limits rational constants to 4096 bits). */
private const val MAX_CONSTANT_BITS = 4096

/**
 * The type of `first op second`. Constants are folded like solc's rational constants (fractional results are
 * [SolRationalConstant]s). Otherwise the result has the common type of the operands like in solc, where a constant
 * converts to its typed operand's type if it fits (`x - 1`) and the typed operand converts to the constant's smallest
 * type otherwise (`uint8 x; x + 300` is `uint16`). A constant shifted or exponentiated by a typed value becomes
 * `uint256`/`int256`. Combinations solc rejects are unknown.
 */
private fun getNumericExpressionType(op: String?, firstType: SolType, secondType: SolType): SolType {
  val first = firstType.constantValue
  val second = secondType.constantValue
  if (first != null && second != null) {
    return foldConstants(op, first, second)?.let { SolRationalConstant.of(it) } ?: SolUnknown
  }
  if (firstType !is SolInteger || secondType !is SolInteger) {
    return SolUnknown
  }
  val isShiftOrExp = op == "<<" || op == ">>" || op == "**"
  return when {
    isShiftOrExp -> if (first != null) SolInteger(first.numerator.signum() >= 0, 256) else firstType.withoutLiteral()
    first != null -> commonTypeWithConstant(secondType, first.numerator)
    second != null -> commonTypeWithConstant(firstType, second.numerator)
    firstType.isAssignableFrom(secondType) -> firstType
    secondType.isAssignableFrom(firstType) -> secondType
    else -> SolUnknown
  }
}

/** solc's common type of the (non-constant) integer [type] and the integer constant [constant]. */
private fun commonTypeWithConstant(type: SolInteger, constant: BigInteger): SolType {
  val typed = type.withoutLiteral()
  if (typed.canHold(constant)) return typed
  val constantType = SolInteger.ofConstant(constant).withoutLiteral()
  return if (constantType.isAssignableFrom(typed)) constantType else SolUnknown
}

private fun foldConstants(op: String?, a: Rational, b: Rational): Rational? {
  val zero = Rational.of(BigInteger.ZERO)
  val result = when (op) {
    "+" -> a + b
    "-" -> a - b
    "*" -> a * b
    "/" -> if (b == zero) null else a / b
    // like solc: a - trunc(a / b) * b, also for fractions (`5.5 % 2` is 3/2)
    "%" -> if (b == zero) null else (a / b).let { q -> a - Rational.of(q.numerator.divide(q.denominator)) * b }
    "**" -> {
      val exp = b.numerator
      val bits = maxOf(a.numerator.bitLength(), a.denominator.bitLength()).toLong()
      when {
        !b.isInteger || exp.bitLength() > 16 || bits * exp.abs().toLong() > MAX_CONSTANT_BITS -> null
        exp.signum() >= 0 -> Rational.of(a.numerator.pow(exp.toInt()), a.denominator.pow(exp.toInt()))
        a == zero -> null
        else -> Rational.of(a.denominator.pow(-exp.toInt()), a.numerator.pow(-exp.toInt()))
      }
    }
    "<<" -> if (!a.isInteger || !b.isInteger || b.numerator.signum() < 0 || b.numerator > BigInteger.valueOf(MAX_CONSTANT_BITS.toLong())) null
      else Rational.of(a.numerator.shiftLeft(b.numerator.toInt()))
    ">>" -> if (!a.isInteger || !b.isInteger || b.numerator.signum() < 0 || b.numerator.bitLength() > 31) null
      else Rational.of(a.numerator.shiftRight(b.numerator.toInt()))
    else -> null
  }
  return result?.takeIf { it.numerator.bitLength() <= MAX_CONSTANT_BITS && it.denominator.bitLength() <= MAX_CONSTANT_BITS }
}

private fun SolCallable.isAbiDecode(): Boolean {
  val function = resolveElement() as? SolFunctionDefinition ?: return false
  return function.name == "decode" && function.contract?.name == internalise("Abi")
}

/** `abi.decode(data, (uint, address))` returns the types listed in its second argument. */
private fun inferAbiDecodeType(call: SolFunctionCallExpression): SolType {
  val types = when (val typesArg = call.functionCallArguments.expressionList.getOrNull(1)) {
    is SolSeqExpression -> typesArg.expressionList.map { typeOfTypeExpression(it) }
    null -> return SolUnknown
    else -> listOf(typeOfTypeExpression(typesArg))
  }
  return when (types.size) {
    0 -> SolUnknown
    1 -> types.single()
    else -> SolTuple(types)
  }
}

/**
 * The type an expression naming a type denotes, as in `abi.decode`'s type list: `uint`, `S`, `L.S`,
 * `uint[2]`, `bytes32[]`, `S[][3]`.
 */
private fun typeOfTypeExpression(expr: SolExpression): SolType = when (expr) {
  is SolIndexAccessExpression -> {
    val expressions = expr.expressionList
    val elementType = expressions.firstOrNull()?.let { typeOfTypeExpression(it) } ?: SolUnknown
    val hasSlice = expr.node.findChildByType(SolidityTokenTypes.COLON) != null
    when {
      elementType == SolUnknown || hasSlice -> SolUnknown
      expressions.size == 1 -> SolDynamicArray(elementType)
      else -> {
        val size = expressions[1].type.constantValue?.takeIf { it.isInteger && it.numerator.signum() > 0 }?.numerator
        if (size != null && size.bitLength() < 31) SolStaticArray(elementType, size.toInt()) else SolUnknown
      }
    }
  }
  else -> when (val type = inferExprType(expr)) {
    is SolUserDefinedValueTypeReferenceType -> SolUserDefinedValueTypeType(type.ref)
    else -> type
  }
}

/** An elementary type name used as an expression, like `bytes` in `bytes.concat(...)` or `uint` in `abi.decode`. */
private val SolExpression.isElementaryTypeNameExpression: Boolean
  get() = this is SolPrimaryExpression && elementaryTypeName != null

fun SolMemberAccessExpression.getMembers(): List<SolMember> {
  val expr = expression
  return when {
    expr.isElementaryTypeNameExpression -> elementaryTypeNameMembers(expr.type, project)
    expr is SolPrimaryExpression && expr.varLiteral?.name == "super" -> {
      val contract = expr.findContract()
      contract?.let { SolResolver.resolveContractMembers(it, true) }
        ?: emptyList()
    }
    else -> {
      val usingFromFunctions = (this as? SolMemberAccessElement)?.collectUsingForLibraryFunctions()
        ?.map { it.toLibraryFunDefinition() } ?: emptyList()
      val stateVarRefs = (expr.reference?.resolve() as? SolStateVariableDeclaration)
        ?.takeIf { v -> v.visibility.isExternallyReferenceable(v.findContract()?.contractType == ContractType.LIBRARY) }
        ?.let { SolVariableType(it).getMembers(it.project) } ?: emptyList()
      val typeMembers = expr.type.getMembers(this.project)
      if (usingFromFunctions.isEmpty() && stateVarRefs.isEmpty()) typeMembers else typeMembers + usingFromFunctions + stateVarRefs
    }
  }
}

val SolExpression.type: SolType
  get() {
    if (!isValid) {
      return SolUnknown
    }
    return RecursionManager.doPreventingRecursion(this, true) {
      CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result.create(inferExprType(this), PsiModificationTracker.MODIFICATION_COUNT)
      }
    } ?: SolUnknown
  }
