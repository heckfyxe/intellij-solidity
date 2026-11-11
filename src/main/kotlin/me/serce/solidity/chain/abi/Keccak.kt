package me.serce.solidity.chain.abi

import org.bouncycastle.crypto.digests.KeccakDigest

/** Keccak-256 as used by Ethereum (original Keccak padding, not SHA3-256). */
object Keccak {
  fun hash(input: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(input, 0, input.size)
    return ByteArray(32).also { digest.doFinal(it, 0) }
  }

  fun hash(text: String): ByteArray = hash(text.toByteArray(Charsets.UTF_8))
}

fun ByteArray.toHex(prefix: Boolean = true): String {
  val sb = StringBuilder(size * 2 + 2)
  if (prefix) sb.append("0x")
  for (b in this) {
    sb.append(Character.forDigit((b.toInt() shr 4) and 0xf, 16))
    sb.append(Character.forDigit(b.toInt() and 0xf, 16))
  }
  return sb.toString()
}

fun String.hexToBytes(): ByteArray {
  val s = removePrefix("0x").removePrefix("0X").let { if (it.length % 2 == 1) "0$it" else it }
  require(s.all { Character.digit(it, 16) >= 0 }) { "Not a hex string: $this" }
  return ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
}
