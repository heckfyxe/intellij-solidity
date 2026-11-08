package me.serce.solidity.chain.abi

import junit.framework.TestCase
import java.math.BigInteger

class AbiCodecTest : TestCase() {
  fun testKeccak() {
    assertEquals("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Keccak.hash("").toHex())
    // longer than one 136-byte block
    assertEquals(
      "0x" + "4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15",
      Keccak.hash("The quick brown fox jumps over the lazy dog").toHex()
    )
    assertEquals(Keccak.hash("a".repeat(200)).size, 32)
  }

  fun testSelectors() {
    val transfer = AbiEntry.Function(
      "transfer",
      listOf(AbiParam("to", AbiType.Address), AbiParam("amount", AbiType.UInt(256))),
      listOf(AbiParam("", AbiType.Bool)),
      Mutability.NONPAYABLE
    )
    assertEquals("0xa9059cbb", transfer.selector.toHex())
    val ev = AbiEntry.Event("Transfer", listOf(
      AbiParam("from", AbiType.Address, indexed = true), AbiParam("to", AbiType.Address, indexed = true), AbiParam("value", AbiType.UInt(256))
    ), false)
    assertEquals("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", ev.topic.toHex())
  }

  fun testChecksum() {
    assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", AbiCodec.checksum("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
  }

  fun testEncodeStatic() {
    val data = AbiCodec.encode(listOf(AbiType.UInt(256), AbiType.Bool, AbiType.Int(8)), listOf(BigInteger.valueOf(69), true, BigInteger.valueOf(-1)))
    assertEquals(
      "0x" +
        "0000000000000000000000000000000000000000000000000000000000000045" +
        "0000000000000000000000000000000000000000000000000000000000000001" +
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      data.toHex()
    )
  }

  /** Example from the Solidity ABI spec: f(uint256,uint32[],bytes10,bytes). */
  fun testEncodeDynamicSpecExample() {
    val types = listOf(AbiType.UInt(256), AbiType.Array(AbiType.UInt(32), null), AbiType.FixedBytes(10), AbiType.Bytes)
    val values = listOf(
      BigInteger.valueOf(0x123),
      listOf(BigInteger.valueOf(0x456), BigInteger.valueOf(0x789)),
      "1234567890".toByteArray(),
      "Hello, world!".toByteArray(),
    )
    val expected = "0x" +
      "0000000000000000000000000000000000000000000000000000000000000123" +
      "0000000000000000000000000000000000000000000000000000000000000080" +
      "3132333435363738393000000000000000000000000000000000000000000000" +
      "00000000000000000000000000000000000000000000000000000000000000e0" +
      "0000000000000000000000000000000000000000000000000000000000000002" +
      "0000000000000000000000000000000000000000000000000000000000000456" +
      "0000000000000000000000000000000000000000000000000000000000000789" +
      "000000000000000000000000000000000000000000000000000000000000000d" +
      "48656c6c6f2c20776f726c642100000000000000000000000000000000000000"
    val encoded = AbiCodec.encode(types, values)
    assertEquals(expected, encoded.toHex())

    val decoded = AbiCodec.decode(types, encoded)
    assertEquals(BigInteger.valueOf(0x123), decoded[0])
    assertEquals(listOf(BigInteger.valueOf(0x456), BigInteger.valueOf(0x789)), decoded[1])
    assertEquals("1234567890", String(decoded[2] as ByteArray))
    assertEquals("Hello, world!", String(decoded[3] as ByteArray))
  }

  fun testRoundTripNestedTuple() {
    val tuple = AbiType.Tuple(listOf(AbiParam("name", AbiType.Str), AbiParam("ids", AbiType.Array(AbiType.UInt(8), 2))))
    val types = listOf(AbiType.Array(tuple, null), AbiType.Address)
    val values = listOf(
      listOf(listOf("alice", listOf(BigInteger.ONE, BigInteger.TWO)), listOf("bob", listOf(BigInteger.TEN, BigInteger.ZERO))),
      "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    )
    assertEquals(values, AbiCodec.decode(types, AbiCodec.encode(types, values)))
  }

  fun testParseAbiJson() {
    val abi = ContractAbi.parse(
      """[{"type":"constructor","inputs":[{"name":"feed","type":"address"}],"stateMutability":"payable"},
         {"type":"function","name":"fund","inputs":[],"outputs":[],"stateMutability":"payable"},
         {"type":"function","name":"orders","inputs":[{"name":"o","type":"tuple[]","internalType":"struct Shop.Order[]",
           "components":[{"name":"id","type":"uint256"},{"name":"buyer","type":"address"}]}],"outputs":[],"stateMutability":"nonpayable"},
         {"type":"error","name":"NotOwner","inputs":[]},
         {"type":"receive","stateMutability":"payable"}]"""
    )
    assertEquals(Mutability.PAYABLE, abi.constructor!!.mutability)
    assertEquals(listOf("fund", "orders"), abi.functions.map { it.name })
    assertEquals("orders((uint256,address)[])", abi.functions[1].signature)
    assertEquals("Shop.Order[]", abi.functions[1].inputs[0].displayType)
    assertEquals(4, abi.entries.size) // receive is skipped
  }

  fun testUnsupportedTypeKeepsTheRestOfTheAbi() {
    val abi = ContractAbi.parse(
      """[{"type":"function","name":"price","inputs":[{"name":"x","type":"fixed128x18"}],"outputs":[],"stateMutability":"view"},
         {"type":"function","name":"ok","inputs":[],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"}]"""
    )
    assertEquals(listOf("price", "ok"), abi.functions.map { it.name })
    assertEquals("price(fixed128x18)", abi.functions[0].signature)
    try {
      AbiValueParser.parse(abi.functions[0].inputs[0].type, "1")
      fail("expected AbiInputException")
    } catch (e: AbiInputException) {
      assertTrue(e.message!!.contains("not supported"))
    }
  }

  fun testReturnDataThatDoesNotMatchTheAbiFailsWithIllegalArgument() {
    // a string's offset word pointing far outside the data, as when an attached ABI doesn't match the contract
    val data = ByteArray(32).also { it.fill(0x7f) }
    try {
      AbiCodec.decode(listOf(AbiType.Str), data)
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }
}
