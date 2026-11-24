package me.serce.solidity.chain.abi

import java.math.BigInteger

/** Left-pads to a 32-byte ABI/storage word; longer input keeps its last 32 bytes. */
fun ByteArray.toWord(): ByteArray = if (size >= 32) copyOfRange(size - 32, size) else ByteArray(32 - size) + this

/** The 32-byte big-endian word of a non-negative number (callers turn negatives into two's complement first). */
fun BigInteger.toWord(): ByteArray = toByteArray().toWord()
