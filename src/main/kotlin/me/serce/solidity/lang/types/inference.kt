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
import kotlin.math.max

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
      val literalValue = (operandType as? SolInteger)?.literalValue
      when (expr.firstChild?.text) {
        "!" -> SolBoolean
        "-" -> if (literalValue != null) SolInteger.ofConstant(literalValue.negate()) else operandType
        "~" -> if (literalValue != null) SolInteger.ofConstant(literalValue.not()) else operandType
        else -> operandType
      }
    }
    is SolMetaTypeExpression -> SolMetaType(getSolType(expr.typeName))
    else -> SolUnknown
  }
}

private fun <E> List<E>.secondOrNull(): E? {
  return if (size < 2) null else this[1]
}

/** Results of constant folding beyond this many bits are not tracked (solc limits rational constants to 4096 bits). */
private const val MAX_CONSTANT_BITS = 4096

/**
 * The type of `first op second`. Constants are folded like solc's rational constants; a constant combined with a
 * typed operand takes the operand's type (`x - 1`), except that a constant shifted or exponentiated by a typed value
 * becomes `uint256`/`int256`.
 */
private fun getNumericExpressionType(op: String?, firstType: SolType, secondType: SolType): SolType {
  if (firstType !is SolInteger || secondType !is SolInteger) {
    return SolUnknown
  }
  val first = firstType.literalValue
  val second = secondType.literalValue
  val isShiftOrExp = op == "<<" || op == ">>" || op == "**"
  return when {
    first != null && second != null -> foldConstants(op, first, second)?.let { SolInteger.ofConstant(it) } ?: SolUnknown
    isShiftOrExp -> if (first != null) SolInteger(first.signum() >= 0, 256) else firstType.withoutLiteral()
    first != null -> secondType.withoutLiteral()
    second != null -> firstType.withoutLiteral()
    else -> SolInteger(firstType.unsigned && secondType.unsigned, max(firstType.size, secondType.size))
  }
}

private fun foldConstants(op: String?, a: BigInteger, b: BigInteger): BigInteger? {
  val result = when (op) {
    "+" -> a + b
    "-" -> a - b
    "*" -> a * b
    // a fractional result is a rational constant, which isn't modelled
    "/" -> if (b.signum() == 0 || a.mod(b.abs()).signum() != 0) null else a / b
    "%" -> if (b.signum() == 0) null else a.rem(b)
    "**" -> if (b.signum() < 0 || b.bitLength() > 16 || a.bitLength().toLong() * b.toLong() > MAX_CONSTANT_BITS) null else a.pow(b.toInt())
    "<<" -> if (b.signum() < 0 || b > BigInteger.valueOf(MAX_CONSTANT_BITS.toLong())) null else a.shiftLeft(b.toInt())
    ">>" -> if (b.signum() < 0 || b.bitLength() > 31) null else a.shiftRight(b.toInt())
    else -> null
  }
  return result?.takeIf { it.bitLength() <= MAX_CONSTANT_BITS }
}

private fun SolCallable.isAbiDecode(): Boolean {
  val function = resolveElement() as? SolFunctionDefinition ?: return false
  return function.name == "decode" && function.contract?.name == internalise("Abi")
}

/** `abi.decode(data, (uint, address))` returns the types listed in its second argument. */
private fun inferAbiDecodeType(call: SolFunctionCallExpression): SolType {
  val types = when (val typesArg = call.functionCallArguments.expressionList.getOrNull(1)) {
    is SolSeqExpression -> typesArg.expressionList.map { inferExprType(it) }
    null -> return SolUnknown
    else -> listOf(inferExprType(typesArg))
  }
  return when (types.size) {
    0 -> SolUnknown
    1 -> types.single()
    else -> SolTuple(types)
  }
}

fun SolMemberAccessExpression.getMembers(): List<SolMember> {
  val expr = expression
  return when {
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
