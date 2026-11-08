package me.serce.solidity.chain.abi

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Standard Solidity ABI encoding. Values are represented as:
 * integers → [BigInteger], address → checksummed `0x` [String], bool → [Boolean],
 * bytes / bytesN → [ByteArray], string → [String], arrays and tuples → [List].
 */
object AbiCodec {
  private val TWO_256 = BigInteger.ONE.shiftLeft(256)

  fun encode(types: List<AbiType>, values: List<Any?>): ByteArray {
    require(types.size == values.size) { "Expected ${types.size} values, got ${values.size}" }
    return encodeTuple(types, values)
  }

  fun encodeCall(selector: ByteArray, types: List<AbiType>, values: List<Any?>): ByteArray =
    selector + encode(types, values)

  private fun encodeTuple(types: List<AbiType>, values: List<Any?>): ByteArray {
    val heads = ArrayList<ByteArray?>()
    val tails = ArrayList<ByteArray>()
    val headSize = types.sumOf { it.headSize }
    for ((t, v) in types.zip(values)) {
      if (t.isDynamic) {
        heads += null
        tails += encodeValue(t, v)
      } else {
        heads += encodeValue(t, v)
        tails += ByteArray(0)
      }
    }
    val out = ByteArrayOutputStream()
    var offset = headSize
    for (i in types.indices) {
      val head = heads[i]
      if (head == null) {
        out.write(uint(BigInteger.valueOf(offset.toLong())))
        offset += tails[i].size
      } else {
        out.write(head)
      }
    }
    tails.forEach { out.write(it) }
    return out.toByteArray()
  }

  private fun encodeValue(t: AbiType, v: Any?): ByteArray = when (t) {
    is AbiType.UInt -> {
      val n = v as BigInteger
      require(n.signum() >= 0 && n.bitLength() <= t.bits) { "$n does not fit in ${t.canonical}" }
      uint(n)
    }
    is AbiType.Int -> {
      val n = v as BigInteger
      require(n.bitLength() < t.bits) { "$n does not fit in ${t.canonical}" }
      uint(if (n.signum() < 0) n.add(TWO_256) else n)
    }
    AbiType.Address -> uint(BigInteger((v as String).removePrefix("0x"), 16))
    AbiType.Bool -> uint(if (v as Boolean) BigInteger.ONE else BigInteger.ZERO)
    is AbiType.FixedBytes -> {
      val b = v as ByteArray
      require(b.size == t.size) { "${t.canonical} needs ${t.size} bytes, got ${b.size}" }
      padRight(b)
    }
    AbiType.Bytes -> dynamicBytes(v as ByteArray)
    AbiType.Str -> dynamicBytes((v as String).toByteArray(Charsets.UTF_8))
    is AbiType.Array -> {
      val list = v as List<*>
      if (t.length != null) require(list.size == t.length) { "${t.canonical} needs ${t.length} elements, got ${list.size}" }
      val body = encodeTuple(List(list.size) { t.element }, list)
      if (t.length == null) uint(BigInteger.valueOf(list.size.toLong())) + body else body
    }
    is AbiType.Tuple -> encodeTuple(t.components.map { it.type }, v as List<*>)
    is AbiType.Unsupported -> throw IllegalArgumentException("${t.canonical} is not supported yet")
  }

  private fun dynamicBytes(b: ByteArray) = uint(BigInteger.valueOf(b.size.toLong())) + padRight(b)

  private fun uint(n: BigInteger): ByteArray {
    val raw = n.toByteArray()
    val out = ByteArray(32)
    val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
    System.arraycopy(src, 0, out, 32 - src.size, src.size)
    return out
  }

  private fun padRight(b: ByteArray): ByteArray = b.copyOf(((b.size + 31) / 32) * 32)

  // ---- decoding ----

  fun decode(types: List<AbiType>, data: ByteArray): List<Any> = decodeTuple(types, data, 0)

  private fun decodeTuple(types: List<AbiType>, data: ByteArray, base: Int): List<Any> {
    var head = base
    return types.map { t ->
      val value = if (t.isDynamic) {
        decodeValue(t, data, base + index(data, head))
      } else {
        decodeValue(t, data, head)
      }
      head += t.headSize
      value
    }
  }

  private fun decodeValue(t: AbiType, data: ByteArray, at: Int): Any = when (t) {
    is AbiType.UInt -> word(data, at)
    is AbiType.Int -> word(data, at).let { if (it.testBit(255)) it.subtract(TWO_256) else it }
    AbiType.Address -> checksum(word(data, at).toString(16).padStart(40, '0').takeLast(40))
    AbiType.Bool -> word(data, at).signum() != 0
    is AbiType.FixedBytes -> slice(data, at, t.size)
    AbiType.Bytes -> slice(data, at + 32, index(data, at))
    AbiType.Str -> String(slice(data, at + 32, index(data, at)), Charsets.UTF_8)
    is AbiType.Array -> if (t.length == null) {
      val n = index(data, at)
      decodeTuple(List(n) { t.element }, data, at + 32)
    } else {
      decodeTuple(List(t.length) { t.element }, data, at)
    }
    is AbiType.Tuple -> decodeTuple(t.components.map { it.type }, data, at)
    is AbiType.Unsupported -> slice(data, at, 32) // shown as raw hex
  }

  /** An offset or length word; anything beyond the data means it doesn't match the ABI. */
  private fun index(data: ByteArray, at: Int): Int {
    val n = word(data, at)
    if (n > BigInteger.valueOf(data.size.toLong())) throw IllegalArgumentException("Return data does not match the ABI")
    return n.toInt()
  }

  private fun word(data: ByteArray, at: Int): BigInteger {
    if (at < 0 || at + 32 > data.size) throw IllegalArgumentException("Return data too short to decode (${data.size} bytes)")
    return BigInteger(1, data.copyOfRange(at, at + 32))
  }

  private fun slice(data: ByteArray, at: Int, len: Int): ByteArray {
    if (at < 0 || at + len > data.size) throw IllegalArgumentException("Return data too short to decode (${data.size} bytes)")
    return data.copyOfRange(at, at + len)
  }

  /** EIP-55 mixed-case checksum address. [hex] is 40 hex chars, with or without 0x. */
  fun checksum(hex: String): String {
    val lower = hex.removePrefix("0x").lowercase()
    val hash = Keccak.hash(lower).toHex(prefix = false)
    return "0x" + lower.mapIndexed { i, c -> if (c.isLetter() && Character.digit(hash[i], 16) >= 8) c.uppercaseChar() else c }
      .joinToString("")
  }
}
