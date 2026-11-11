package me.serce.solidity.chain.abi

import java.math.BigDecimal
import java.math.BigInteger

class AbiInputException(message: String) : IllegalArgumentException(message)

/** Turns what the user typed into ABI values, converting ether units on the way. */
object AbiValueParser {
  val UNITS: Map<String, Int> = mapOf(
    "wei" to 0, "kwei" to 3, "mwei" to 6, "gwei" to 9, "szabo" to 12, "finney" to 15, "ether" to 18, "eth" to 18,
  )
  private val NUMBER_WITH_UNIT = Regex("""^([+-]?[0-9][0-9_]*(?:\.[0-9_]*)?(?:e[+-]?\d+)?)\s*([a-z]*)$""", RegexOption.IGNORE_CASE)

  fun parse(type: AbiType, text: String): Any {
    val s = text.trim()
    return when (type) {
      is AbiType.UInt, is AbiType.Int -> parseInteger(s, type)
      AbiType.Address -> parseAddress(s)
      AbiType.Bool -> when (s.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> throw AbiInputException("Expected true or false")
      }
      is AbiType.FixedBytes -> {
        val b = parseHex(s)
        if (b.size > type.size) throw AbiInputException("${type.canonical} holds ${type.size} bytes, got ${b.size}")
        b.copyOf(type.size)
      }
      AbiType.Bytes -> parseHex(s)
      AbiType.Str -> unquote(s)
      is AbiType.Array -> {
        val items = splitList(s)
        if (type.length != null && items.size != type.length) {
          throw AbiInputException("${type.canonical} needs ${type.length} elements, got ${items.size}")
        }
        items.map { parse(type.element, it) }
      }
      is AbiType.Tuple -> {
        val items = splitList(s)
        if (items.size != type.components.size) {
          throw AbiInputException("Expected ${type.components.size} fields, got ${items.size}")
        }
        type.components.zip(items).map { (p, v) -> parse(p.type, v) }
      }
      is AbiType.Unsupported -> throw AbiInputException("${type.canonical} is not supported yet")
    }
  }

  /** Integer value of [text] (`1000`, `0x3e8`, `1e18`, `0.5 eth`, `20 gwei`) or null when it is not a number. */
  fun parseAmount(text: String): BigInteger? {
    val s = text.trim().replace(",", "")
    if (s.isEmpty()) return BigInteger.ZERO
    if (s.startsWith("0x", ignoreCase = true)) return s.drop(2).toBigIntegerOrNull(16)
    val m = NUMBER_WITH_UNIT.find(s) ?: return null
    val (num, unit) = m.destructured
    val decimals = if (unit.isEmpty()) 0 else UNITS[unit.lowercase()] ?: return null
    val value = BigDecimal(num.replace("_", "")).movePointRight(decimals)
    return try {
      value.toBigIntegerExact()
    } catch (_: ArithmeticException) {
      null
    }
  }

  private fun parseInteger(s: String, type: AbiType): BigInteger {
    val n = parseAmount(s) ?: throw AbiInputException("Not a whole number: '$s'. Try 1000, 0x3e8, 1e18 or 0.5 eth")
    when (type) {
      is AbiType.UInt -> if (n.signum() < 0 || n.bitLength() > type.bits) throw AbiInputException("Out of range for ${type.canonical}")
      is AbiType.Int -> if (n.bitLength() >= type.bits) throw AbiInputException("Out of range for ${type.canonical}")
      else -> {}
    }
    return n
  }

  private fun parseAddress(s: String): String {
    val hex = unquote(s).removePrefix("0x").removePrefix("0X")
    if (hex.length != 40 || hex.any { Character.digit(it, 16) < 0 }) throw AbiInputException("Not an address: '$s'")
    val expected = AbiCodec.checksum(hex)
    val mixed = hex.any { it.isUpperCase() } && hex.any { it.isLowerCase() }
    if (mixed && "0x$hex" != expected) throw AbiInputException("Address checksum mismatch, did you mean $expected?")
    return expected
  }

  private fun parseHex(s: String): ByteArray {
    val t = unquote(s)
    if (t.isEmpty() || t == "0x") return ByteArray(0)
    if (!t.startsWith("0x", ignoreCase = true)) throw AbiInputException("Bytes must be hex starting with 0x")
    return try {
      t.hexToBytes()
    } catch (e: IllegalArgumentException) {
      throw AbiInputException("Not valid hex: '$s'")
    }
  }

  private fun unquote(s: String): String =
    if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s

  /** Splits `[a, [b, c], "d,e"]` or `(a, b)` into its top-level items. */
  fun splitList(s: String): List<String> {
    val t = s.trim()
    val open = t.firstOrNull()
    val close = if (open == '[') ']' else if (open == '(') ')' else null
    if (close == null || t.last() != close) throw AbiInputException("Expected a list like [a, b]")
    val body = t.substring(1, t.length - 1)
    if (body.isBlank()) return emptyList()
    val items = ArrayList<String>()
    var depth = 0
    var quote: Char? = null
    val cur = StringBuilder()
    for (c in body) {
      when {
        quote != null -> { if (c == quote) quote = null; cur.append(c) }
        c == '"' || c == '\'' -> { quote = c; cur.append(c) }
        c == '[' || c == '(' -> { depth++; cur.append(c) }
        c == ']' || c == ')' -> { depth--; cur.append(c) }
        c == ',' && depth == 0 -> { items += cur.toString().trim(); cur.clear() }
        else -> cur.append(c)
      }
    }
    items += cur.toString().trim()
    return items
  }
}

object AbiValueFormatter {
  private val SUPERSCRIPT = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')

  fun format(value: Any?): String = when (value) {
    null -> "null"
    is BigInteger -> value.toString()
    is ByteArray -> value.toHex()
    is String -> value
    is List<*> -> value.joinToString(", ", "[", "]") { item -> if (item is String && !item.startsWith("0x")) "\"$item\"" else format(item) }
    else -> value.toString()
  }

  /** `2225.45 × 10¹⁸` for large integers, null when the plain number is readable enough. */
  fun readable(value: BigInteger): String? {
    val abs = value.abs()
    val exp = when {
      abs >= BigInteger.TEN.pow(15) -> 18
      abs >= BigInteger.TEN.pow(7) -> 9
      else -> return null
    }
    val scaled = BigDecimal(value).movePointLeft(exp).stripTrailingZeros()
    val rounded = if (scaled.scale() > 4) scaled.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros() else scaled
    val prefix = if (rounded.compareTo(scaled) != 0) "≈ " else ""
    return "$prefix${rounded.toPlainString()} × 10${exp.toString().map { SUPERSCRIPT[it] }.joinToString("")}"
  }

  /** Formats wei as `1.5 ETH`. */
  fun ether(wei: BigInteger, decimals: Int = 6): String {
    val eth = BigDecimal(wei).movePointLeft(18).stripTrailingZeros()
    val shown = if (eth.scale() > decimals) eth.setScale(decimals, java.math.RoundingMode.DOWN).stripTrailingZeros() else eth
    return "${shown.toPlainString()} ETH"
  }
}

/** Decodes revert data into something readable. */
object RevertDecoder {
  const val NO_REASON = "reverted without a reason"
  private const val ERROR_STRING = "08c379a0"
  private const val PANIC = "4e487b71"
  private val PANIC_CODES = mapOf(
    0x00 to "generic compiler panic", 0x01 to "assert failed", 0x11 to "arithmetic overflow or underflow",
    0x12 to "division or modulo by zero", 0x21 to "invalid enum value", 0x22 to "corrupted storage byte array",
    0x31 to "pop() on empty array", 0x32 to "array index out of bounds", 0x41 to "out of memory",
    0x51 to "call to zero-initialized function",
  )

  fun decode(data: ByteArray, errors: List<AbiEntry.Error>): String = decodeOrNull(data, errors) ?: unknownError(data)

  /** Like [decode], but null when [data] is a custom error that none of [errors] declares. */
  fun decodeOrNull(data: ByteArray, errors: List<AbiEntry.Error>): String? {
    if (data.isEmpty()) return NO_REASON
    if (data.size < 4) return "reverted with ${data.toHex()}"
    val selectorBytes = data.copyOf(4)
    val selector = selectorBytes.toHex(prefix = false)
    val body = data.copyOfRange(4, data.size)
    return try {
      when (selector) {
        ERROR_STRING -> AbiCodec.decode(listOf(AbiType.Str), body)[0] as String
        PANIC -> {
          val code = (AbiCodec.decode(listOf(AbiType.UInt(256)), body)[0] as BigInteger).toInt()
          "panic 0x${code.toString(16).padStart(2, '0')}: ${PANIC_CODES[code] ?: "unknown"}"
        }
        else -> {
          val error = errors.firstOrNull { it.selector.contentEquals(selectorBytes) } ?: return null
          val values = AbiCodec.decode(error.inputs.map { it.type }, body)
          "${error.name}(${error.inputs.zip(values).joinToString(", ") { (p, v) -> (if (p.name.isNotEmpty()) "${p.name}: " else "") + AbiValueFormatter.format(v) }})"
        }
      }
    } catch (e: IllegalArgumentException) {
      "reverted with ${data.toHex()}"
    }
  }

  private fun unknownError(data: ByteArray): String {
    val body = data.copyOfRange(4, data.size)
    return "reverted with unknown error ${data.copyOf(4).toHex()}${if (body.isNotEmpty()) " (${body.toHex()})" else ""}"
  }
}
