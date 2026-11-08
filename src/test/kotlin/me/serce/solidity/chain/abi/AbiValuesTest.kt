package me.serce.solidity.chain.abi

import junit.framework.TestCase
import me.serce.solidity.chain.foundry.SolcLocator
import java.math.BigInteger

class AbiValuesTest : TestCase() {
  private fun uint(text: String) = AbiValueParser.parse(AbiType.UInt(256), text)

  fun testUnits() {
    assertEquals(BigInteger("500000000000000000"), uint("0.5 eth"))
    assertEquals(BigInteger("500000000000000000"), uint("0.5eth"))
    assertEquals(BigInteger("1000000000000000000"), uint("1 ether"))
    assertEquals(BigInteger("20000000000"), uint("20 gwei"))
    assertEquals(BigInteger("1000"), uint("1000 wei"))
    assertEquals(BigInteger("1000000000000000000"), uint("1e18"))
    assertEquals(BigInteger("1000000"), uint("1_000_000"))
    assertEquals(BigInteger("255"), uint("0xff"))
  }

  fun testRejectsBadIntegers() {
    assertThrows { uint("0.5") }
    assertThrows { uint("1.5 wei") }
    assertThrows { uint("-1") }
    assertThrows { uint("ten") }
    assertThrows { AbiValueParser.parse(AbiType.UInt(8), "256") }
    assertEquals(BigInteger.valueOf(-128), AbiValueParser.parse(AbiType.Int(8), "-128"))
    assertThrows { AbiValueParser.parse(AbiType.Int(8), "128") }
  }

  fun testAddress() {
    assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", AbiValueParser.parse(AbiType.Address, "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
    assertThrows { AbiValueParser.parse(AbiType.Address, "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed") } // bad checksum
    assertThrows { AbiValueParser.parse(AbiType.Address, "0x1234") }
  }

  fun testListsAndTuples() {
    val arr = AbiType.Array(AbiType.UInt(256), null)
    assertEquals(listOf(BigInteger.ONE, BigInteger("1000000000000000000")), AbiValueParser.parse(arr, "[1, 1 eth]"))
    assertEquals(emptyList<Any>(), AbiValueParser.parse(arr, "[]"))
    val tuple = AbiType.Tuple(listOf(AbiParam("s", AbiType.Str), AbiParam("xs", AbiType.Array(AbiType.Bool, null))))
    assertEquals(listOf("a, b", listOf(true, false)), AbiValueParser.parse(tuple, "(\"a, b\", [true, false])"))
    assertThrows { AbiValueParser.parse(AbiType.Array(AbiType.UInt(8), 2), "[1]") }
  }

  fun testBytes() {
    assertEquals("0x01020000", (AbiValueParser.parse(AbiType.FixedBytes(4), "0x0102") as ByteArray).toHex())
    assertThrows { AbiValueParser.parse(AbiType.Bytes, "hello") }
  }

  fun testReadable() {
    assertEquals("2225.45 × 10¹⁸", AbiValueFormatter.readable(BigInteger("2225450000000000000000")))
    assertEquals("≈ 3.1416 × 10⁹", AbiValueFormatter.readable(BigInteger("3141592653")))
    assertNull(AbiValueFormatter.readable(BigInteger("12345")))
    assertEquals("1.5 ETH", AbiValueFormatter.ether(BigInteger("1500000000000000000")))
  }

  fun testRevertReasons() {
    val reason = "08c379a0".hexToBytes() + AbiCodec.encode(listOf(AbiType.Str), listOf("Didn't send enough ETH"))
    assertEquals("Didn't send enough ETH", RevertDecoder.decode(reason, emptyList()))
    val panic = "4e487b71".hexToBytes() + AbiCodec.encode(listOf(AbiType.UInt(256)), listOf(BigInteger.valueOf(0x11)))
    assertEquals("panic 0x11: arithmetic overflow or underflow", RevertDecoder.decode(panic, emptyList()))
    val err = AbiEntry.Error("NotOwner", listOf(AbiParam("caller", AbiType.Address)))
    val custom = err.selector + AbiCodec.encode(listOf(AbiType.Address), listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    assertEquals("NotOwner(caller: 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed)", RevertDecoder.decode(custom, listOf(err)))
    assertEquals("reverted without a reason", RevertDecoder.decode(ByteArray(0), emptyList()))
  }

  fun testPragmaRanges() {
    val v = { s: String -> SolcLocator.Version.parse(s)!! }
    assertTrue(SolcLocator.matches("^0.8.34", v("0.8.38")))
    assertFalse(SolcLocator.matches("^0.8.34", v("0.8.24")))
    assertFalse(SolcLocator.matches("^0.8.0", v("0.9.0")))
    assertTrue(SolcLocator.matches(">=0.8.0 <0.9.0", v("0.8.19")))
    assertFalse(SolcLocator.matches(">=0.8.0 <0.9.0", v("0.9.0")))
    assertTrue(SolcLocator.matches("0.8.20", v("0.8.20")))
    assertFalse(SolcLocator.matches("0.8.20", v("0.8.21")))
    assertTrue(SolcLocator.matches("^0.7.0 || ^0.8.0", v("0.8.1")))
    assertTrue(SolcLocator.matches("~0.8.1", v("0.8.9")))
  }

  private fun assertThrows(block: () -> Unit) {
    try {
      block()
    } catch (e: IllegalArgumentException) {
      return
    }
    fail("Expected an exception")
  }
}
