package me.serce.solidity.chain.storage

import me.serce.solidity.util.hexToBytes
import me.serce.solidity.util.checksumAddress
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SystemProperties
import me.serce.solidity.chain.abi.AbiInputException
import me.serce.solidity.chain.abi.toWord
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.rpc.JsonRpcClient
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket

/** Decodes a real contract's storage against anvil. Skipped without Foundry. */
class StorageLayoutTest : BasePlatformTestCase() {
  private val bin = File(SystemProperties.getUserHome(), ".foundry/bin")
  private var anvil: Process? = null

  override fun tearDown() {
    try {
      anvil?.destroy()
    } finally {
      super.tearDown()
    }
  }

  fun testParseSlotAndDescribeWord() {
    assertEquals(BigInteger.valueOf(5), StorageLayout.parseSlot(" 5 "))
    assertEquals(BigInteger.valueOf(255), StorageLayout.parseSlot("0xff"))
    assertThrows { StorageLayout.parseSlot("-1") }
    assertThrows { StorageLayout.parseSlot("abc") }
    val word = ("0x000000000000000000000000" + "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed").hexToBytes()
    assertTrue(StorageDialog.describeWord(word), StorageDialog.describeWord(word).contains("address 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    assertEquals("-1", StorageLayout.decodeValue(byteArrayOf(-1), "int8"))
    assertEquals("255", StorageLayout.decodeValue(byteArrayOf(-1), "uint8"))
  }

  fun testDecodePackedTypes() {
    // slot 0: int8 -3 at offset 0, bool true at 1, address 0xdead at 2; slot 1: uint256 42
    val slot0 = ("0x" + "00".repeat(10) + "000000000000000000000000000000000000dead" + "01" + "fd").hexToBytes()
    val slot1 = BigInteger.valueOf(42).toWord()
    val reader = StorageReader { if (it.signum() == 0) slot0 else slot1 }
    assertEquals(
      listOf("slot 0  int8: -3", "slot 0 +1  bool: true", "slot 0 +2  address: ${checksumAddress("0x000000000000000000000000000000000000dead")}"),
      StorageLayout.decodePacked("int8, bool, address", BigInteger.ZERO, reader),
    )
    // a uint256 doesn't fit after the int8, so it is read from the next slot
    assertEquals(listOf("slot 0  uint8: 253", "slot 1  uint256: 42"), StorageLayout.decodePacked("uint8,uint256", BigInteger.ZERO, reader))
    assertEquals(listOf("slot 0 +1  uint8: 1"), StorageLayout.decodePacked("bytes1, uint8", BigInteger.ZERO, reader).drop(1))
    assertThrows { StorageLayout.decodePacked("string", BigInteger.ZERO, reader) }
    assertThrows { StorageLayout.decodePacked(" ", BigInteger.ZERO, reader) }
  }

  private fun assertThrows(body: () -> Unit) {
    try {
      body()
      fail("no exception")
    } catch (e: Exception) {
    }
  }

  fun testParseAccessPath() {
    assertEquals(listOf("[0xBEEF]"), StorageLayout.parsePath(" 0xBEEF ").map { it.toString() })
    assertEquals(listOf("[1]", "[0xab]", ".amount"), StorageLayout.parsePath("[1][0xab].amount").map { it.toString() })
    assertEquals(listOf("[\"a]b\"]", "[2]"), StorageLayout.parsePath("[\"a]b\"][2]").map { it.toString() })
    assertThrows { StorageLayout.parsePath("[1") }
    assertThrows { StorageLayout.parsePath("[1]x") }
    assertThrows { StorageLayout.parsePath("[1].") }
  }

  fun testDecodesArrays() {
    val (layout, reader) = deploy(
      """
      contract S {
          struct P { uint128 a; address b; }
          uint8[] small;
          uint256[3] fixedArr = [uint256(4), 5, 6];
          uint8[4] packedFixed = [7, 8, 9, 10];
          uint256[] empty;
          P[] ps;
          uint256[][] grid;
          mapping(address => uint256[]) lists;
          string[] names;
          bool[2][] flags;
          constructor() {
              small.push(1); small.push(2); small.push(3);
              for (uint8 i = 0; i < 40; i++) small.push(i);
              ps.push(P(7, address(0xCAFE))); ps.push(P(8, address(0xBEEF)));
              grid.push(); grid.push(); grid[1].push(11); grid[1].push(12);
              lists[address(0xBEEF)].push(21); lists[address(0xBEEF)].push(22);
              names.push("x"); names.push("a string that is longer than thirty-one bytes");
              flags.push([true, false]);
          }
      }
      """.trimIndent()
    ) ?: return
    val rows = layout.rows().associateBy { it.name }
    fun value(name: String) = layout.read(rows.getValue(name), reader)
    fun at(name: String, path: String) = layout.read(layout.access(rows.getValue(name), path, reader), reader)

    assertEquals("length 43: [1, 2, 3, 0, 1, 2, 3, 4, 5, 6, …]", value("small"))
    assertEquals("[4, 5, 6]", value("fixedArr"))
    assertEquals("[7, 8, 9, 10]", value("packedFixed"))
    assertEquals("length 0", value("empty"))
    assertTrue(value("ps"), value("ps").startsWith("length 2, elements from slot 0x"))

    // uint8 elements are packed 32 to a slot: element 34 is in the second slot
    assertEquals("3", at("small", "2"))
    assertEquals("31", at("small", "[34]"))
    assertEquals("6", at("fixedArr", "2"))
    assertEquals("10", at("packedFixed", "[3]"))
    assertEquals("{a: 8, b: ${checksumAddress("0x000000000000000000000000000000000000beef")}}", at("ps", "1"))
    assertEquals("7", at("ps", "[0].a"))
    assertEquals("length 2: [11, 12]", at("grid", "1"))
    assertEquals("12", at("grid", "[1][1]"))
    assertEquals("length 0", at("grid", "[0]"))
    assertEquals("length 2: [21, 22]", at("lists", "0x000000000000000000000000000000000000BEEF"))
    assertEquals("22", at("lists", "[0x000000000000000000000000000000000000BEEF][1]"))
    assertEquals("\"x\"", at("names", "0"))
    assertEquals("\"a string that is longer than thirty-one bytes\"", at("names", "1"))
    assertEquals("[true, false]", at("flags", "0"))
    assertEquals("false", at("flags", "[0][1]"))

    assertError("out of bounds", { at("small", "43") })
    assertError("out of bounds", { at("fixedArr", "3") })
    assertError("out of bounds", { at("grid", "[0][0]") })
    assertError("not a struct", { at("small", "[0].a") })
    assertError("no member", { at("ps", "[0].c") })
    assertError("Enter an index", { at("small", "x") })
  }

  private fun assertError(part: String, body: () -> Unit) {
    try {
      body()
      fail("no exception")
    } catch (e: AbiInputException) {
      assertTrue(e.message, e.message!!.contains(part))
    }
  }

  /** Builds [source] in a temp Foundry project and deploys contract `S` on a fresh anvil; null without Foundry. */
  private fun deploy(source: String): Pair<StorageLayout, StorageReader>? {
    if (!File(bin, "anvil").canExecute() || !File(bin, "forge").canExecute()) return null
    val root = FileUtil.createTempDirectory("storage", null)
    File(root, "foundry.toml").writeText("[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
    File(root, "src").mkdirs()
    File(root, "src/S.sol").writeText("// SPDX-License-Identifier: MIT\npragma solidity ^0.8.20;\n$source\n")
    val foundry = FoundryProject(root.toPath(), File(bin, "forge").path)
    assertTrue(foundry.build().success)
    val artifact = foundry.artifacts().single { it.name == "S" }
    val layout = StorageLayout.parse(foundry.storageLayout("src/S.sol", "S"))
    val port = ServerSocket(0).use { it.localPort }
    anvil = GeneralCommandLine(File(bin, "anvil").path, "--port", port.toString()).createProcess()
    val rpc = JsonRpcClient("http://127.0.0.1:$port")
    for (i in 1..50) if (runCatching { rpc.chainId() }.isSuccess) break else Thread.sleep(200)
    val hash = rpc.sendTransaction(rpc.accounts().first(), null, artifact.bytecode.hexToBytes(), BigInteger.ZERO)
    val address = checksumAddress(rpc.waitForReceipt(hash)["contractAddress"].asText())
    return layout to StorageReader { slot -> rpc.storageAt(address, slot.toHex32()) }
  }

  fun testDecodesStateVariablesAndMappings() {
    if (!File(bin, "anvil").canExecute() || !File(bin, "forge").canExecute()) return
    val root = FileUtil.createTempDirectory("storage", null)
    File(root, "foundry.toml").writeText("[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
    File(root, "src").mkdirs()
    File(root, "src/S.sol").writeText(
      """
      // SPDX-License-Identifier: MIT
      pragma solidity ^0.8.20;
      contract S {
          enum E { A, B }
          struct P { uint128 a; address b; }
          int8 small = -3;
          bool flag = true;
          address owner = address(0xdEaD);
          uint256 big = 42;
          mapping(address => uint256) bal;
          uint256[] arr;
          string name = "hi";
          string long = "a string that is longer than thirty-one bytes";
          P p;
          E e = E.B;
          mapping(string => mapping(uint256 => bool)) nested;
          constructor() {
              bal[address(0xBEEF)] = 5;
              arr.push(1); arr.push(2);
              p = P(7, address(0xCAFE));
              nested["k"][3] = true;
          }
      }
      """.trimIndent()
    )
    val foundry = FoundryProject(root.toPath(), File(bin, "forge").path)
    assertTrue(foundry.build().success)
    val artifact = foundry.artifacts().single { it.name == "S" }
    val layout = StorageLayout.parse(foundry.storageLayout("src/S.sol", "S"))

    val port = ServerSocket(0).use { it.localPort }
    anvil = GeneralCommandLine(File(bin, "anvil").path, "--port", port.toString()).createProcess()
    val rpc = JsonRpcClient("http://127.0.0.1:$port")
    for (i in 1..50) if (runCatching { rpc.chainId() }.isSuccess) break else Thread.sleep(200)
    val hash = rpc.sendTransaction(rpc.accounts().first(), null, artifact.bytecode.hexToBytes(), BigInteger.ZERO)
    val address = checksumAddress(rpc.waitForReceipt(hash)["contractAddress"].asText())
    val reader = StorageReader { slot -> rpc.storageAt(address, slot.toHex32()) }

    val rows = layout.rows().associateBy { it.name }
    fun value(name: String) = layout.read(rows.getValue(name), reader)
    assertEquals("-3", value("small"))
    assertEquals("true", value("flag"))
    assertEquals(checksumAddress("0x000000000000000000000000000000000000dead"), value("owner"))
    assertEquals("42", value("big"))
    assertTrue(value("arr"), value("arr").startsWith("length 2"))
    assertEquals("\"hi\"", value("name"))
    assertEquals("\"a string that is longer than thirty-one bytes\"", value("long"))
    assertEquals("7", value("p.a"))
    assertEquals(checksumAddress("0x000000000000000000000000000000000000cafe"), value("p.b"))
    assertEquals("1", value("e"))
    assertEquals("5", layout.readMapping(rows.getValue("bal"), "0x000000000000000000000000000000000000BEEF", reader))
    assertEquals("0", layout.readMapping(rows.getValue("bal"), "0x0000000000000000000000000000000000000001", reader))
    assertTrue(layout.readMapping(rows.getValue("nested"), "k", reader).startsWith("mapping at slot 0x"))
  }
}
