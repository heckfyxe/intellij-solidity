package me.serce.solidity.util

import java.math.BigInteger
import java.util.HexFormat

private val HEX = HexFormat.of()

/** [this] without a leading `0x` or `0X`. */
fun String.withoutHexPrefix(): String = removePrefix("0x").removePrefix("0X")

/** Lower-case hex, `0x`-prefixed unless [prefix] is false. */
fun ByteArray.toHex(prefix: Boolean = true): String = (if (prefix) "0x" else "") + HEX.formatHex(this)

/** Hex with or without `0x`; an odd number of digits gets a leading zero. */
fun String.hexToBytes(): ByteArray {
  val s = withoutHexPrefix().let { if (it.length % 2 == 1) "0$it" else it }
  require(isHexDigits(s)) { "Not a hex string: $this" }
  return HEX.parseHex(s)
}

/** A hex quantity with or without `0x`; empty is zero. */
fun String.hexToBigInt(): BigInteger = withoutHexPrefix().ifEmpty { "0" }.toBigInteger(16)

/** A JSON-RPC quantity: `0x`-prefixed hex without leading zeros. */
fun BigInteger.toHexQuantity(): String = "0x" + toString(16)

fun isHexDigits(text: CharSequence): Boolean = text.all { HexFormat.isHexDigit(it.code) }
