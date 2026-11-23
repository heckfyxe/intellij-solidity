package me.serce.solidity.chain.abi

import junit.framework.TestCase

class AbiFetcherTest : TestCase() {
  private val proxy = "0x1111111111111111111111111111111111111111"
  private val impl = "0x2222222222222222222222222222222222222222"
  private val requested = ArrayList<String>()

  private fun fetcher(key: String?, responses: Map<String, Pair<Int, String>>) = AbiFetcher({ key }) { url ->
    requested += url
    responses.entries.firstOrNull { url.contains(it.key) }?.value ?: (404 to "{}")
  }

  fun testSourcifyFirst() {
    val f = fetcher("k", mapOf("sourcify.dev/server/v2/contract/1/$proxy" to (200 to """{"abi":[{"type":"function","name":"a","inputs":[],"outputs":[]}],"compilation":{"name":"Feed"}}""")))
    val abi = f.fetch(null, 1, proxy)
    assertEquals("Feed", abi.name)
    assertEquals("Sourcify", abi.source)
    assertTrue(requested.none { it.contains("etherscan") })
  }

  fun testEtherscanProxyGetsImplementationAbi() {
    val proxyAbi = """[{\"type\":\"function\",\"name\":\"upgradeTo\",\"inputs\":[{\"name\":\"i\",\"type\":\"address\"}],\"outputs\":[]},{\"type\":\"function\",\"name\":\"a\",\"inputs\":[],\"outputs\":[]}]"""
    val implAbi = """[{\"type\":\"function\",\"name\":\"a\",\"inputs\":[],\"outputs\":[]},{\"type\":\"function\",\"name\":\"b\",\"inputs\":[],\"outputs\":[]}]"""
    val f = fetcher("secret", mapOf(
      "address=$proxy" to (200 to """{"status":"1","message":"OK","result":[{"ABI":"$proxyAbi","ContractName":"TransparentUpgradeableProxy","Proxy":"1","Implementation":"$impl"}]}"""),
      "address=$impl" to (200 to """{"status":"1","message":"OK","result":[{"ABI":"$implAbi","ContractName":"Vault","Proxy":"0","Implementation":""}]}"""),
    ))
    val abi = f.fetch(null, 11155111, proxy)
    assertEquals("Vault", abi.name)
    assertEquals("Etherscan", abi.source)
    assertEquals(impl, abi.implementation)
    assertEquals(listOf("a", "b", "upgradeTo"), ContractAbi.parse(abi.json).functions.map { it.name })
    assertTrue(requested.any { it.contains("chainid=11155111") })
  }

  fun testNotVerified() {
    try {
      fetcher(null, emptyMap()).fetch(null, 1, proxy)
      fail()
    } catch (e: AbiFetchException) {
      assertTrue(e.message, e.message!!.contains("add an Etherscan API key"))
    }
    val unverified = fetcher("k", mapOf("etherscan" to (200 to """{"status":"1","message":"OK","result":[{"ABI":"Contract source code not verified","ContractName":""}]}""")))
    try {
      unverified.fetch(null, 1, proxy)
      fail()
    } catch (e: AbiFetchException) {
    }
  }

  fun testEtherscanErrorDoesNotLeakKey() {
    val f = fetcher("my-secret-key", mapOf("etherscan" to (200 to """{"status":"0","message":"NOTOK","result":"Invalid API Key (#err2)|my-secret-key"}""")))
    try {
      f.fetch(null, 1, proxy)
      fail()
    } catch (e: AbiFetchException) {
      assertTrue(e.message, e.message!!.startsWith("Etherscan: Invalid API Key"))
      assertFalse(e.message, e.message!!.contains("my-secret-key"))
    }
  }

  fun testStructNamesForInterfaces() {
    val json = AbiText.parse("function place(tuple(address maker, tuple(uint a) inner)[] orders) returns (tuple(uint id))").json
    val named = ContractAbi.parse(AbiText.withStructNames(json, "IBook")).functions.single()
    assertEquals("struct IBook.PlaceOrders[]", named.inputs[0].internalType)
    assertEquals("struct IBook.PlaceResult0", named.outputs[0].internalType)
    assertEquals("struct IBook.PlaceOrdersInner", (named.inputs[0].type as AbiType.Array).element.let { it as AbiType.Tuple }.components[1].internalType)
  }
}
