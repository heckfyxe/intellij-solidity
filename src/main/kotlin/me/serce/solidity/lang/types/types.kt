package me.serce.solidity.lang.types

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.Linearizable
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.types.SolInteger.Companion.UINT_160
import java.math.BigInteger
import java.util.*

// http://solidity.readthedocs.io/en/develop/types.html

enum class ContextType {
  SUPER,
  EXTERNAL,
  BUILTIN // for accessing a state variable within a builtin contract definition
}

enum class Usage {
  VARIABLE,
  CALLABLE
}

interface SolMember {
  fun getName(): String?
  fun parseType(): SolType
  fun resolveElement(): SolNamedElement?
  fun getPossibleUsage(contextType: ContextType): Usage?
}

interface SolType {
  fun isAssignableFrom(other: SolType): Boolean
  fun getMembers(project: Project): List<SolMember> {
    return emptyList()
  }
  val isBuiltin: Boolean
    get() = true
}

interface SolUserType : SolType {
  override val isBuiltin: Boolean
    get() = false

  val abiName: String
}

interface SolPrimitiveType : SolType
interface SolNumeric : SolPrimitiveType

object SolUnknown : SolPrimitiveType {
  override fun isAssignableFrom(other: SolType): Boolean = false

  override fun toString() = "<unknown>"
}

object SolErrorType : SolPrimitiveType {
  override fun isAssignableFrom(other: SolType): Boolean = other == this

  override fun toString() = "error"
}

object SolBoolean : SolPrimitiveType {
  override fun isAssignableFrom(other: SolType): Boolean =
    other == SolBoolean

  override fun toString() = "bool"
}

data class SolString(val length: Int) : SolPrimitiveType {
  override fun isAssignableFrom(other: SolType): Boolean =
    other is SolString

  override fun toString() = "string"

  companion object {
    fun inferType(stringLiteral: SolStringLiteral): SolString {
      return SolString(stringLiteral.text
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .length)
    }
  }
}

class SolAddress(val isPayable : Boolean) : SolPrimitiveType {
  private val toString = "address${if (isPayable) " payable" else ""}"
  override fun isAssignableFrom(other: SolType): Boolean =
    when (other) {
      is SolAddress -> !this.isPayable || other.isPayable
      is SolContract -> other.ref.isPayable.let { !this.isPayable || it }
      else -> UINT_160.isAssignableFrom(other)
    }

  override fun getMembers(project: Project) = getSdkMembers(SolInternalTypeFactory.of(project).addressType)

  override fun toString() = toString

  companion object {
    val PAYABLE = SolAddress(true)
    val NON_PAYABLE = SolAddress(false)
  }
}

enum class NumericLiteralType {
  HEX, DECIMAL, SCIENTIFIC, ZERO
}

/**
 * An integer type. Number literals are typed as the smallest integer type holding their value and also carry that
 * value in [literalValue], so that (like solc's rational constants) they are assignable to every integer type the
 * value fits in, e.g. `int8 x = -128`.
 */
data class SolInteger(
  val unsigned: Boolean,
  val size: Int,
  val digitCount: Int? = null,
  val literalType: NumericLiteralType? = null,
  val literalValue: BigInteger? = null
) : SolNumeric {
  companion object {
    val UINT_160 = SolInteger(true, 160)
    val UINT_256 = SolInteger(true, 256)

    fun parse(name: String): SolInteger {
      var unsigned = false
      var size = 256
      var typeName = name
      if (name.startsWith("u")) {
        unsigned = true
        typeName = typeName.substring(1)
      }
      if (!typeName.startsWith("int")) {
        throw IllegalArgumentException("Incorrect int typename: $name")
      }
      typeName = typeName.substring(3)
      if (typeName.isNotEmpty()) {
        try {
          size = Integer.parseInt(typeName)
        } catch (e: NumberFormatException) {
          throw IllegalArgumentException("Incorrect int typename: $name")
        }
      }
      return SolInteger(unsigned, size)
    }

    /**
     * The type of a number literal: an integer constant, or a [SolRationalConstant] when its value (after applying the
     * unit, e.g. `0.5 ether`) is fractional. [SolUnknown] when the value can't be computed (e.g. a huge exponent).
     */
    fun inferType(numberLiteral: SolNumberLiteral): SolType {
      val parsed = numberLiteral.parseLiteral() ?: return SolUnknown
      val value = parsed.value
      return if (value.isInteger) {
        inferIntegerType(LiteralParseResult(value.numerator, parsed.type, parsed.digitCount))
      } else {
        SolRationalConstant(value)
      }
    }

    fun inferType(numberLiteral: SolHexLiteral): SolInteger? {
      val withoutPrefix = numberLiteral.text.substring(3) //
        .removeSurrounding("\"") //
        .removeSurrounding("'") //
        .replace("_", "") // underscore is an allowed separator in hex literals
      val value = when {
          withoutPrefix.isEmpty() -> BigInteger.ZERO
          else -> try {
            withoutPrefix.toBigInteger(16)
          } catch (_: NumberFormatException) {
            null
          }
      }
      if (value == null) {
        return null
      }
      val parse = LiteralParseResult(value, NumericLiteralType.HEX, withoutPrefix.length)
      return inferIntegerType(parse)
    }

    /** The type of a number constant with the given [value], e.g. the result of `-1` or `2**8 - 1`. */
    fun ofConstant(value: BigInteger): SolInteger =
      inferIntegerType(LiteralParseResult(value, NumericLiteralType.DECIMAL))

    private fun inferIntegerType(parseResult: LiteralParseResult): SolInteger {
      val value = parseResult.value
      if (value == BigInteger.ZERO) return SolInteger(true, 8, 1, NumericLiteralType.ZERO, BigInteger.ZERO)
      val positive = value >= BigInteger.ZERO
      if (positive) {
        var shifts = 0
        var current = value
        while (current != BigInteger.ZERO) {
          shifts++
          current = current.shiftRight(8)
        }
        return SolInteger(positive, shifts * 8, parseResult.digitCount, parseResult.type, value)
      } else {
        var shifts = 1
        var current = value.abs().minus(BigInteger.ONE).shiftRight(7)
        while (current != BigInteger.ZERO) {
          shifts++
          current = current.shiftRight(8)
        }
        return SolInteger(positive, shifts * 8, parseResult.digitCount, parseResult.type, value)
      }
    }

    private class LiteralParseResult(val value: BigInteger, val type: NumericLiteralType, val digitCount: Int? = null)

    private class NumberLiteralValue(val value: Rational, val type: NumericLiteralType, val digitCount: Int? = null)

    private val UNITS = mapOf(
      "wei" to BigInteger.ONE,
      "gwei" to BigInteger.TEN.pow(9),
      "szabo" to BigInteger.TEN.pow(12),
      "finney" to BigInteger.TEN.pow(15),
      "ether" to BigInteger.TEN.pow(18),
      "seconds" to BigInteger.ONE,
      "minutes" to BigInteger.valueOf(60),
      "hours" to BigInteger.valueOf(3600),
      "days" to BigInteger.valueOf(86400),
      "weeks" to BigInteger.valueOf(604800),
      "years" to BigInteger.valueOf(31536000),
    )

    /** Literals like `1e5000` are far beyond any value solc accepts, so they are left untyped. */
    private const val MAX_LITERAL_EXPONENT = 1300

    private fun SolNumberLiteral.parseLiteral(): NumberLiteralValue? {
      val unitElement = numberUnit
      val unit = unitElement?.text
      val multiplier = if (unit == null) BigInteger.ONE else UNITS[unit] ?: return null
      val numberText = (if (unitElement != null) text.substring(0, unitElement.startOffsetInParent) else text)
        .trim().replace("_", "")
      if (numberText.startsWith("0x") || numberText.startsWith("0X")) {
        val digits = numberText.substring(2)
        val value = digits.toBigIntegerOrNull(16) ?: return null
        return if (unit == null) NumberLiteralValue(Rational.of(value), NumericLiteralType.HEX, digits.length)
        else NumberLiteralValue(Rational.of(value * multiplier), NumericLiteralType.DECIMAL)
      }
      val lower = numberText.lowercase()
      val exponentIndex = lower.indexOf('e')
      val mantissa = if (exponentIndex >= 0) lower.substring(0, exponentIndex) else lower
      val exponent = if (exponentIndex >= 0) {
        lower.substring(exponentIndex + 1).removePrefix("+").toIntOrNull() ?: return null
      } else 0
      val dot = mantissa.indexOf('.')
      val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
      val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
      val digits = (intPart + fracPart).ifEmpty { return null }
      val scale = exponent.toLong() - fracPart.length
      if (kotlin.math.abs(scale) > MAX_LITERAL_EXPONENT) return null
      var numerator = (digits.toBigIntegerOrNull() ?: return null) * multiplier
      var denominator = BigInteger.ONE
      if (scale >= 0) numerator *= BigInteger.TEN.pow(scale.toInt()) else denominator = BigInteger.TEN.pow((-scale).toInt())
      val type = if (exponentIndex >= 0) NumericLiteralType.SCIENTIFIC else NumericLiteralType.DECIMAL
      return NumberLiteralValue(Rational.of(numerator, denominator), type)
    }
  }

  val isLiteral: Boolean
    get() = literalValue != null

  /** This type without the literal information, i.e. the type of a variable of this type. */
  fun withoutLiteral(): SolInteger =
    if (literalType == null && digitCount == null && literalValue == null) this else SolInteger(unsigned, size)

  /** Whether [value] is representable by this type. */
  fun canHold(value: BigInteger): Boolean =
    if (unsigned) value.signum() >= 0 && value.bitLength() <= size else value.bitLength() < size

  override fun isAssignableFrom(other: SolType): Boolean =
    when (other) {
      is SolInteger -> {
        val literalValue = other.literalValue
        if (literalValue != null) {
          canHold(literalValue)
        } else if (this.unsigned && !other.unsigned) {
          false
        } else if (!this.unsigned && other.unsigned) {
          this.size - 2 >= other.size
        } else {
          this.size >= other.size
        }
      }
      else -> false
    }

  override fun toString() = "${if (unsigned) "u" else ""}int$size"
}

/** A rational number, always normalized: the denominator is positive and coprime with the numerator. */
class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
  val isInteger: Boolean get() = denominator == BigInteger.ONE

  operator fun plus(other: Rational) = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
  operator fun minus(other: Rational) = of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
  operator fun times(other: Rational) = of(numerator * other.numerator, denominator * other.denominator)
  /** Requires [other] to be non-zero. */
  operator fun div(other: Rational) = of(numerator * other.denominator, denominator * other.numerator)
  operator fun unaryMinus() = Rational(numerator.negate(), denominator)

  override fun equals(other: Any?) = other is Rational && numerator == other.numerator && denominator == other.denominator
  override fun hashCode() = Objects.hash(numerator, denominator)
  override fun toString() = if (isInteger) "$numerator" else "$numerator/$denominator"

  companion object {
    fun of(value: BigInteger) = Rational(value, BigInteger.ONE)

    fun of(numerator: BigInteger, denominator: BigInteger): Rational {
      require(denominator.signum() != 0) { "zero denominator" }
      val gcd = numerator.gcd(denominator)
      val sign = BigInteger.valueOf(denominator.signum().toLong())
      return Rational(numerator / gcd * sign, denominator / gcd * sign)
    }
  }
}

/**
 * A fractional number constant like `1/2` or `2.5` (solc's `rational_const`). It isn't implicitly convertible to any
 * type; constants that fold to an integer (`2.5 * 2`, `0.5 ether`) are typed as [SolInteger] constants instead.
 */
data class SolRationalConstant(val value: Rational) : SolNumeric {
  override fun isAssignableFrom(other: SolType): Boolean = other == this

  override fun toString() = "rational_const ${formatConstant(value.numerator)} / ${formatConstant(value.denominator)}"

  companion object {
    /** The type of the constant [value]: an integer constant when it is integral. */
    fun of(value: Rational): SolType =
      if (value.isInteger) SolInteger.ofConstant(value.numerator) else SolRationalConstant(value)
  }
}

/** The value of a number constant type, or `null` if [this] isn't a constant. */
val SolType.constantValue: Rational?
  get() = when (this) {
    is SolInteger -> literalValue?.let { Rational.of(it) }
    is SolRationalConstant -> value
    else -> null
  }

/** Long numbers are abbreviated like solc does, e.g. `1157...(70 digits omitted)...9935`. */
private fun formatConstant(value: BigInteger): String {
  val digits = value.abs().toString()
  val sign = if (value.signum() < 0) "-" else ""
  if (digits.length <= 32) return sign + digits
  return "$sign${digits.take(4)}...(${digits.length - 8} digits omitted)...${digits.takeLast(4)}"
}

/** How this type is named in compiler-style messages: number constants are `int_const 5` / `rational_const 1 / 2`. */
fun SolType.displayName(): String {
  val value = (this as? SolInteger)?.literalValue
  return if (value != null) "int_const ${formatConstant(value)}" else toString()
}

data class SolContract(val ref: SolContractDefinition, val builtin: Boolean = false) : SolUserType, Linearizable<SolContract> {
  override fun linearize(): List<SolContract> {
    return RecursionManager.doPreventingRecursion(ref, true) {
      CachedValuesManager.getCachedValue(ref) {
        CachedValueProvider.Result.create(super.linearize(), PsiModificationTracker.MODIFICATION_COUNT)
      }
    } ?: emptyList()
  }

  override fun linearizeParents(): List<SolContract> {
    return RecursionManager.doPreventingRecursion(ref, true) {
      CachedValuesManager.getCachedValue(ref) {
        CachedValueProvider.Result.create(super.linearizeParents(), PsiModificationTracker.MODIFICATION_COUNT)
      }
    } ?: emptyList()
  }

  override fun getParents(): List<SolContract> {
    return ref.supers
    .flatMap { it.reference?.multiResolve() ?: emptyList() }
    .filterIsInstance<SolContractDefinition>()
    .map { SolContract(it) }
    .asReversed()
  }

  override fun isAssignableFrom(other: SolType): Boolean =
    when (other) {
      is SolContract -> {
        other.ref == ref
          || other.ref.collectSupers.any { ref in SolResolver.resolveTypeNameUsingImports(it) }
      }
      else -> false
    }

  override fun getMembers(project: Project): List<SolMember> {
    return SolResolver.resolveContractMembers(ref, false)
  }

  override val isBuiltin get() = builtin
  override val abiName: String
    get() = "contract"

  override fun toString() = ref.name ?: ref.text ?: "$ref"
}

data class SolStruct(val ref: SolStructDefinition, val builtin : Boolean = false) : SolUserType, SolMember {
  override fun isAssignableFrom(other: SolType): Boolean =
    other is SolStruct && ref == other.ref

  override fun toString() = ref.name ?: ref.text ?: "$ref"

  override fun getMembers(project: Project): List<SolMember> {
    return ref.variableDeclarationList
      .map { SolStructVariableDeclaration(it) }
  }

  override val isBuiltin: Boolean
    get() = builtin

  override fun getName(): String? = ref.name

  override fun parseType(): SolType = this

  override fun resolveElement(): SolNamedElement? = ref

  override fun getPossibleUsage(contextType: ContextType): Usage? = null
  override val abiName: String
    get() = "struct"
}

data class SolStructVariableDeclaration(
  val ref: SolVariableDeclaration
) : SolMember {
  override fun getName(): String? = ref.name

  override fun parseType(): SolType = getSolType(ref.typeName)

  override fun resolveElement(): SolNamedElement? = ref

  override fun getPossibleUsage(contextType: ContextType) = Usage.VARIABLE
}

data class SolMemberConstructor<T>(val ref: T) : SolMember, SolCallable where T : SolCallable, T: SolNamedElement  {
  override val callablePriority: Int = 0

  override fun getName(): String? = ref.name

  override fun parseType(): SolType = ref.parseType()
  override fun parseParameters(): List<Pair<String?, SolType>> = ref.parseParameters()

  override fun resolveElement(): SolNamedElement = ref

  override fun getPossibleUsage(contextType: ContextType): Usage = Usage.CALLABLE

}

data class SolEnum(val ref: SolEnumDefinition) : SolUserType, SolMember {
  override val abiName: String
    get() = "enum"
  override fun isAssignableFrom(other: SolType): Boolean =
    other is SolEnum && ref == other.ref

  override fun getName(): String? = ref.name

  override fun parseType(): SolType = this

  override fun resolveElement(): SolNamedElement? = ref

  override fun getPossibleUsage(contextType: ContextType): Usage? = null

  override fun toString() = ref.name ?: ref.text ?: "$ref"

  override fun getMembers(project: Project): List<SolMember> {
    return ref.enumValueList
  }
}


data class SolMapping(val from: SolType, val to: SolType) : SolType {
  override fun isAssignableFrom(other: SolType): Boolean =
    other is SolMapping && from == other.from && to == other.to

  override fun toString(): String {
    return "mapping($from => $to)"
  }
}

data class SolTuple(val types: List<SolType>) : SolType {
  override fun isAssignableFrom(other: SolType): Boolean = false

  override fun toString(): String {
    return "(${types.joinToString(separator = ",") { it.toString() }})"
  }
}

sealed class SolArray(val type: SolType) : SolType {
  class SolStaticArray(type: SolType, val size: Int) : SolArray(type) {
    override fun isAssignableFrom(other: SolType): Boolean =
      other is SolStaticArray && other.type == type && other.size == size

    override fun toString() = "$type[$size]"

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SolStaticArray

      if (size != other.size) return false
      if (type != other.type) return false
      return true
    }

    override fun hashCode(): Int {
      return Objects.hash(size, type)
    }

    override fun getMembers(project: Project) = SolInternalTypeFactory.of(project).arrayType.ref.stateVariableDeclarationList
  }

  open class SolDynamicArray(type: SolType) : SolArray(type) {
    override fun isAssignableFrom(other: SolType): Boolean =
      other is SolDynamicArray && type == other.type

    override fun toString() = "$type[]"

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SolDynamicArray

      if (type != other.type) return false
      return true
    }

    override fun hashCode(): Int {
      return type.hashCode()
    }

    override fun getMembers(project: Project): List<SolMember> = getSdkMembers(SolInternalTypeFactory.of(project).arrayType)
  }
}

object SolBytes : SolArray.SolDynamicArray(SolFixedByte(1)) {
  // bytes.concat takes any number of bytes, bytesN and string/hex literals; the arguments are left unchecked
  internal val concatFunction = BuiltinCallable(listOf(SolInternalTypeFactory.varargsId to SolUnknown), SolBytes, "concat", null)

  override fun isAssignableFrom(other: SolType): Boolean =
    other == SolBytes || other is SolString

  override fun toString() = "bytes"
}

/**
 * Members of an elementary type name used as an expression, e.g. `bytes` in `bytes.concat(...)`: only `bytes.concat`
 * and `string.concat` exist, values of these types have no `concat` member.
 */
fun elementaryTypeNameMembers(type: SolType, project: Project): List<SolMember> = when (type) {
  is SolBytes -> listOf(SolBytes.concatFunction)
  is SolString -> getSdkMembers(SolInternalTypeFactory.of(project).stringType)
  else -> emptyList()
}

data class SolFixedByte(val size: Int): SolPrimitiveType {
  override fun toString() = "byte$size"

  override fun isAssignableFrom(other: SolType): Boolean =
    other is SolFixedByte && other.size <= size

  companion object {

    val regex = "byte\\d*$".toRegex()
    fun parse(name: String): SolFixedByte {
      return if (name.startsWith("byte")) {
        SolFixedByte(name.substring(4).toInt())
      } else {
        throw java.lang.IllegalArgumentException("should start with 'byte'")
      }
    }
  }
}


data class SolFixedBytes(val size: Int): SolPrimitiveType {

  override fun toString() = "bytes$size"

  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolFixedBytes && other.size <= size ||
      other is SolInteger &&
      (other.literalType == NumericLiteralType.HEX && other.digitCount == size * 2 || other.literalType == NumericLiteralType.ZERO) ||
      other is SolString && (other.length == size || other.length == 0)
  }

  override fun getMembers(project: Project) = getSdkMembers(SolInternalTypeFactory.of(project).fixedBytesType)

  companion object {
    val regex = "bytes\\d*$".toRegex()
    fun parse(name: String): SolFixedBytes {
      return if (name.startsWith("bytes")) {
        SolFixedBytes(name.substring(5).toInt())
      } else {
        throw java.lang.IllegalArgumentException("should start with bytes")
      }
    }
  }
}

/**
 * The type of `type(T)`. [enumMinMax] is false where the pragma rules out `type(E).min`/`max` for enums
 * (added in Solidity 0.8.8).
 */
data class SolMetaType(val type: SolType, val enumMinMax: Boolean = true): SolType {
  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolMetaType && this.type == other.type
  }

  /**
   * `min`/`max` (of type T) for integer and enum types; `name`, `creationCode`/`runtimeCode` for contracts (only
   * `name` for abstract ones) and `name`/`interfaceId` for interfaces. An unknown T gets them all.
   */
  override fun getMembers(project: Project): List<SolMember> {
    val all = getSdkMembers(SolInternalTypeFactory.of(project).metaType)
    fun select(vararg names: String) = all.filter { it.getName() in names }
    return when (type) {
      is SolInteger -> select("min", "max").map { SolRetypedMember(it, type) }
      is SolEnum -> if (enumMinMax) select("min", "max").map { SolRetypedMember(it, type) } else emptyList()
      is SolContract -> when {
        type.ref.contractType == ContractType.INTERFACE -> select("name", "interfaceId")
        type.ref.isAbstract -> select("name")
        else -> select("name", "creationCode", "runtimeCode")
      }
      else -> all
    }
  }

  override fun toString(): String = "type($type)"
}

/** A builtin member whose declared type depends on where it is accessed, like `type(T).max` being of type T. */
data class SolRetypedMember(private val member: SolMember, private val type: SolType) : SolMember by member {
  override fun parseType(): SolType = type
}

/** The type of an error name, e.g. `MyError` in `MyError.selector`. */
data class SolErrorReferenceType(val ref: SolErrorDefinition) : SolType {
  override fun isAssignableFrom(other: SolType): Boolean = other == this

  override fun getMembers(project: Project): List<SolMember> =
    getSdkMembers(SolInternalTypeFactory.of(project).errorReferenceType)

  override fun toString(): String = "error ${ref.name}"
}

/** The type of an event name, e.g. `Transfer` in `Transfer.selector`. */
data class SolEventReferenceType(val ref: SolEventDefinition) : SolType {
  override fun isAssignableFrom(other: SolType): Boolean = other == this

  override fun getMembers(project: Project): List<SolMember> =
    getSdkMembers(SolInternalTypeFactory.of(project).eventReferenceType)

  override fun toString(): String = "event ${ref.name}"
}

data class SolFunctionType(val ref: SolFunctionDefinition): SolType {
  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolFunctionType && this.ref.parseParameters() == other.ref.parseParameters() && this.ref.parseType() == other.ref.parseType() // todo state mutability of 'this' is more restrictive than the state mutability of 'other'
  }

  override fun getMembers(project: Project): List<SolMember> {
    return getReferenceTypeMembers(project)
  }



  override fun toString(): String = "function(${ref.name})"
}

data class SolVariableType(val ref: SolStateVariableDeclaration): SolType {
  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolVariableType && this.ref.parseParameters() == other.ref.parseParameters() && this.ref.parseType() == other.ref.parseType()
  }

  override fun getMembers(project: Project): List<SolMember> {
    return getReferenceTypeMembers(project)
  }

  override fun toString(): String = "variable(${ref.name})"
}

private fun getReferenceTypeMembers(project: Project) =
    getSdkMembers(SolInternalTypeFactory.of(project).functionType).map {
      it.getName()?.let { name -> if (it is SolCallable && name.startsWith("__")) changeName(it, name.substring(2)) else it }
        ?: it
    }

data class SolUserDefinedValueTypeReferenceType(val ref: SolUserDefinedValueTypeDefinition): SolType {
  val elementaryType: SolType = getSolType(ref.elementaryTypeName)
  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolUserDefinedValueTypeReferenceType && this.elementaryType == other.elementaryType
  }

  override fun getMembers(project: Project): List<SolMember> {
    return listOf(BuiltinCallable(listOf("value" to this), elementaryType, "unwrap"), BuiltinCallable(listOf("value" to elementaryType), this, "wrap"))
  }

  override fun toString(): String = "ValueTypeReference(${ref.name})"
}

data class SolUserDefinedValueTypeType(val ref: SolUserDefinedValueTypeDefinition): SolType {
  val elementaryType: SolType = getSolType(ref.elementaryTypeName)
  override fun isAssignableFrom(other: SolType): Boolean {
    return other is SolUserDefinedValueTypeType && this.elementaryType == other.elementaryType
  }

  override fun toString(): String = "ValueType(${ref.name})"
}




data class SolFunctionReference(val ref: SolFunctionDefinition): SolMember {
  override fun getName(): String? = ref.name

  override fun parseType(): SolType {
    return SolFunctionType(ref)
  }

  override fun resolveElement(): SolNamedElement? = ref

  override fun getPossibleUsage(contextType: ContextType): Usage? = Usage.VARIABLE

}

data class SolFunctionTypeType(val ref: SolFunctionTypeName): SolType {
  override fun isAssignableFrom(other: SolType): Boolean = when (other) {
    is SolFunctionTypeType -> this.ref == other.ref
    is SolFunctionType -> {
      val func = other.ref
      this.ref.params.zip(func.parameters).all { getSolType(it.first.typeName).isAssignableFrom(getSolType(it.second.typeName)) } &&
        this.ref.returns.isAssignableFrom(func.parseType()) &&
        this.ref.mutability == other.ref.mutability && this.ref.visibility == other.ref.visibility
    }
    else -> false
  }

  override fun toString(): String {
    return "functionType(${ref.params.joinToString {it.text}}) ${ref.returns.takeIf { it != SolUnknown }?.let { " returns ($it)" } ?: ""}"
  }
}

fun SolParameterList?.parseType(): SolType = when (this) {
  null -> SolUnknown
  else -> parameterDefList.let {
    when (it.size) {
      1 -> getSolType(it[0].typeName)
      else -> SolTuple(it.map { def -> getSolType(def.typeName) })
    }
  }
}



private const val INTERNAL_INDICATOR = "_sol1_s"

fun internalise(name: String): String = "$name$INTERNAL_INDICATOR"

fun isInternal(name: String): Boolean = name.endsWith(INTERNAL_INDICATOR)

fun deInternalise(name: String): String = when {
  name.endsWith(INTERNAL_INDICATOR) -> name.removeSuffix(INTERNAL_INDICATOR)
  else -> name
}

class BuiltinType(
  private val name: String,
  private val members: List<SolMember>
) : SolType {
  override fun isAssignableFrom(other: SolType): Boolean = false
  override fun getMembers(project: Project): List<SolMember> = members
  override fun toString(): String = name
}

data class BuiltinCallable(
  private val parameters: List<Pair<String?, SolType>>,
  private val returnType: SolType,
  private val memberName: String?,
  private val resolvedElement: SolNamedElement? = null,
  private val possibleUsage: Usage = Usage.CALLABLE
) : SolCallable, SolMember {
  override val callablePriority: Int
    get() = 1000
  override fun parseParameters(): List<Pair<String?, SolType>> = parameters
  override fun parseType(): SolType = returnType
  override fun resolveElement(): SolNamedElement? = resolvedElement
  override fun getName(): String? = memberName
  override fun getPossibleUsage(contextType: ContextType) = possibleUsage
}




private fun getSdkMembers(solContract: SolContract): List<SolMember> =
  solContract.ref.let { it.functionDefinitionList + it.stateVariableDeclarationList }

private fun changeName(it: SolCallable, newName: String): SolMember {
  return BuiltinCallable(it.parseParameters(), it.parseType(), newName)
}
