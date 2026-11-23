package me.serce.solidity.chain.abi

import junit.framework.TestCase

class AbiTextTest : TestCase() {
  private fun abi(text: String) = ContractAbi.parse(AbiText.parse(text).json)

  private fun assertRejected(text: String, message: String) {
    try {
      AbiText.parse(text)
      fail("accepted: $text")
    } catch (e: AbiInputException) {
      assertTrue(e.message, e.message!!.contains(message))
    }
  }

  fun testJsonArray() {
    val parsed = abi("""[{"type":"function","name":"totalSupply","inputs":[],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"}]""")
    assertEquals("totalSupply()", parsed.functions.single().signature)
  }

  fun testArtifactAndExplorerResponses() {
    val artifact = AbiText.parse("""{"contractName":"Token","abi":[{"type":"function","name":"f","inputs":[],"outputs":[]}],"bytecode":"0x"}""")
    assertEquals("Token", artifact.name)
    assertEquals("f()", ContractAbi.parse(artifact.json).functions.single().signature)
    val etherscan = AbiText.parse(
      """{"status":"1","result":[{"ContractName":"Vault","ABI":"[{\"type\":\"function\",\"name\":\"g\",\"inputs\":[{\"name\":\"a\",\"type\":\"address\"}],\"outputs\":[]}]"}]}"""
    )
    assertEquals("Vault", etherscan.name)
    assertEquals("g(address)", ContractAbi.parse(etherscan.json).functions.single().signature)
    val sourcify = AbiText.parse("""{"abi":[{"type":"event","name":"E","inputs":[],"anonymous":false}],"compilation":{"name":"Feed"}}""")
    assertEquals("Feed", sourcify.name)
  }

  fun testHumanReadableSignatures() {
    val parsed = abi(
      """
      function balanceOf(address owner) view returns (uint256)
      function transfer(address to, uint amount) returns (bool)
      function deposit() payable
      event Transfer(address indexed from, address indexed to, uint256 value)
      error Insufficient(uint256 available, uint256 required)
      function getOrder(uint256 id) external view returns (tuple(address maker, uint96 amount)[] orders, bytes32)
      """
    )
    val fns = parsed.functions.associateBy { it.name }
    assertEquals(Mutability.VIEW, fns.getValue("balanceOf").mutability)
    assertEquals("transfer(address,uint256)", fns.getValue("transfer").signature)
    assertEquals(Mutability.PAYABLE, fns.getValue("deposit").mutability)
    assertEquals(Mutability.NONPAYABLE, fns.getValue("transfer").mutability)
    assertEquals("(address,uint96)[]", fns.getValue("getOrder").outputs[0].type.canonical)
    assertEquals("orders", fns.getValue("getOrder").outputs[0].name)
    assertEquals(listOf(true, true, false), parsed.events.single().inputs.map { it.indexed })
    assertEquals("Insufficient(uint256,uint256)", parsed.errors.single().signature)
  }

  fun testSolidityInterface() {
    val text = """
      // SPDX-License-Identifier: MIT
      pragma solidity ^0.8.0;

      /// @notice Chainlink price feed
      interface AggregatorV3Interface is Base {
        function decimals() external view returns (uint8);
        /* multi-line
           comment */
        function latestRoundData()
          external
          view
          returns (uint80 roundId, int256 answer, uint256 startedAt, uint256 updatedAt, uint80 answeredInRound);
        function setName(string calldata name) external override(A, B);
      }
    """
    val parsed = AbiText.parse(text)
    assertEquals("AggregatorV3Interface", parsed.name)
    val abi = ContractAbi.parse(parsed.json)
    assertEquals(listOf("decimals", "latestRoundData", "setName"), abi.functions.map { it.name })
    assertEquals(5, abi.functions[1].outputs.size)
    assertEquals("setName(string)", abi.functions[2].signature)
  }

  fun testRejectsWhatCannotBeEncoded() {
    assertRejected("", "empty")
    assertRejected("function f(Order o)", "Unknown type 'Order'")
    assertRejected("struct S { uint a; }", "tuple(")
    assertRejected("[]", "no functions")
    assertRejected("{\"foo\": 1}", "No ABI")
    assertRejected("hello world", "Expected function")
    assertRejected("function f(uint a", "end of the ABI")
  }
}
