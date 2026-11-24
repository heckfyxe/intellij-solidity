package me.serce.solidity.util

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

/** A `0x`-prefixed address in text, in any case. */
val ADDRESS_REGEX = Regex("0x[0-9a-fA-F]{40}")

/** 40 hex digits, with or without `0x`, in any case (the checksum is not checked). */
fun isAddress(text: String): Boolean = text.trim().withoutHexPrefix().let { it.length == 40 && isHexDigits(it) }

/** The EIP-55 mixed-case checksum address of [hex]: 40 hex digits, with or without `0x`, in any case. */
fun checksumAddress(hex: String): String {
  val lower = hex.trim().withoutHexPrefix().lowercase()
  val hash = Keccak.hash(lower.toByteArray(Charsets.US_ASCII))
  return "0x" + lower.mapIndexed { i, c ->
    val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0F
    if (c in 'a'..'f' && nibble >= 8) c.uppercaseChar() else c
  }.joinToString("")
}
