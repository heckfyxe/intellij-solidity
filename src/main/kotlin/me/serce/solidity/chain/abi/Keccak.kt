package me.serce.solidity.chain.abi

/** Keccak-256 as used by Ethereum (original Keccak padding, not SHA3-256). */
object Keccak {
  private val RC = listOf(
    0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
    0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
    0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
    0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
    0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
    0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL
  ).map { it.toLong() }.toLongArray()
  private val ROT = intArrayOf(
    0, 1, 62, 28, 27,
    36, 44, 6, 55, 20,
    3, 10, 43, 25, 39,
    41, 45, 15, 21, 8,
    18, 2, 61, 56, 14
  )
  private const val RATE = 136

  fun hash(input: ByteArray): ByteArray {
    val state = LongArray(25)
    val padded = (input.size / RATE + 1) * RATE
    val data = input.copyOf(padded)
    data[input.size] = (data[input.size].toInt() xor 0x01).toByte()
    data[padded - 1] = (data[padded - 1].toInt() xor 0x80).toByte()
    for (block in 0 until padded step RATE) {
      for (i in 0 until RATE / 8) {
        var lane = 0L
        for (b in 0 until 8) lane = lane or ((data[block + i * 8 + b].toLong() and 0xff) shl (8 * b))
        state[i] = state[i] xor lane
      }
      permute(state)
    }
    val out = ByteArray(32)
    for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
    return out
  }

  fun hash(text: String): ByteArray = hash(text.toByteArray(Charsets.UTF_8))

  private fun permute(a: LongArray) {
    val c = LongArray(5)
    val b = LongArray(25)
    for (round in 0 until 24) {
      for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
      for (x in 0 until 5) {
        val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
        for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
      }
      for (x in 0 until 5) for (y in 0 until 5) {
        b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
      }
      for (y in 0 until 25 step 5) for (x in 0 until 5) {
        a[y + x] = b[y + x] xor (b[y + (x + 1) % 5].inv() and b[y + (x + 2) % 5])
      }
      a[0] = a[0] xor RC[round]
    }
  }
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
