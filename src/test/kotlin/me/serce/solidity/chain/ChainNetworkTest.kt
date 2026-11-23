package me.serce.solidity.chain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SystemProperties
import com.intellij.util.ui.UIUtil
import me.serce.solidity.chain.abi.AbiText
import me.serce.solidity.chain.abi.hexToBytes
import me.serce.solidity.chain.rpc.JsonRpcClient
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket

/** Network chains: a real network stood in for by an anvil with a public chain id. Skipped without Foundry. */
class ChainNetworkTest : BasePlatformTestCase() {
  private val bin = File(SystemProperties.getUserHome(), ".foundry/bin")
  private var anvil: Process? = null
  private lateinit var savedNetworks: List<Network>

  override fun setUp() {
    super.setUp()
    savedNetworks = Networks.getInstance().list
  }

  override fun tearDown() {
    try {
      anvil?.destroy()
      Networks.getInstance().list = savedNetworks
    } finally {
      super.tearDown()
    }
  }

  private fun waitFor(rt: ChainRuntime, seconds: Int): ChainStatus {
    val deadline = System.currentTimeMillis() + seconds * 1000
    while (rt.status == ChainStatus.STARTING && System.currentTimeMillis() < deadline) {
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(100)
    }
    UIUtil.dispatchAllInvocationEvents()
    return rt.status
  }

  /** Starts anvil as "Sepolia" and adds it to the networks under [name]. */
  private fun startNetwork(name: String): JsonRpcClient {
    val port = ServerSocket(0).use { it.localPort }
    anvil = GeneralCommandLine(File(bin, "anvil").path, "--port", port.toString(), "--chain-id", "11155111").createProcess()
    val rpc = JsonRpcClient("http://127.0.0.1:$port/?key=secret")
    for (i in 1..50) if (runCatching { rpc.chainId() }.isSuccess) break else Thread.sleep(200)
    Networks.getInstance().list = savedNetworks + Network(name, rpc.url)
    return rpc
  }

  fun testCustomNetworkUrlsAreKeptInPasswordSafe() {
    val networks = Networks.getInstance()
    networks.list = savedNetworks + Network("Mine", "https://eth-mainnet.g.alchemy.com/v2/abc123")
    val stored = networks.state.networks.single { it.name == "Mine" }
    assertTrue(stored.secret)
    assertEquals("", stored.url)
    assertEquals("https://eth-mainnet.g.alchemy.com/v2/abc123", networks.find("Mine")!!.url)
    assertFalse(networks.state.networks.single { it.name == "Sepolia" }.secret)
    networks.list = savedNetworks
    assertNull(Secrets.get(Secrets.networkUrlKey("Mine")))
    assertEquals("https://eth-mainnet.g.alchemy.com", JsonRpcClient.redact("https://eth-mainnet.g.alchemy.com/v2/abc123"))
  }

  fun testCallsWithoutWalletAndAbiOnly() {
    if (!File(bin, "anvil").canExecute()) return
    val rpc = startNetwork("Test net")
    // a contract that returns 42 for every call
    val hash = rpc.sendTransaction(rpc.accounts().first(), null, "0x600a600c600039600a6000f3602a60005260206000f3".hexToBytes(), BigInteger.ZERO)
    val address = rpc.waitForReceipt(hash)["contractAddress"].asText()

    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { kind = ChainKind.NETWORK; network = "Test net"; name = "Test net" }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 30))
    assertEquals("11155111", chain.expectedChainId)
    assertEquals(emptyList<String>(), rt.accounts) // the node's unlocked accounts are not ours on a network
    assertEquals("http://127.0.0.1:${rpc.url.substringAfterLast(':').substringBefore('/')}", rt.rpc!!.displayUrl)

    val d = Deployment().apply {
      chainId = chain.id; name = "Answer"; this.address = address
      abiJson = AbiText.parse("function answer() view returns (uint256)\nfunction poke()").json
    }
    service.addDeployment(d)
    UIUtil.dispatchAllInvocationEvents()
    val executor = ChainExecutor(service)
    val fns = d.abi().functions.associateBy { it.name }
    val call = executor.invoke(chain, d, fns.getValue("answer"), emptyList(), null, BigInteger.ZERO)
    assertEquals(call.record.error, TxStatus.SUCCESS, call.record.status)
    assertTrue(call.record.results.toString(), call.record.results.single().contains("42"))
    val tx = executor.invoke(chain, d, fns.getValue("poke"), emptyList(), null, BigInteger.ZERO)
    assertEquals(TxStatus.FAILED, tx.record.status)
    assertTrue(tx.record.error, tx.record.error.contains("Add a wallet"))
    service.stop(chain)
  }

  fun testRefusesNetworkWithAnotherChainId() {
    if (!File(bin, "anvil").canExecute()) return
    startNetwork("Moved")
    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { kind = ChainKind.NETWORK; network = "Moved"; name = "Mainnet"; expectedChainId = "1" }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(ChainStatus.FAILED, waitFor(rt, 30))
    assertTrue(rt.error, rt.error!!.contains("reports chain id 11155111"))
    assertNull(rt.rpc)
  }

  fun testMainnetDetection() {
    assertTrue(KnownChains.isMainnet(1))
    assertFalse(KnownChains.isMainnet(11155111))
    assertTrue(KnownChains.isMainnet(987654321)) // unknown: treated as real money
    assertFalse(KnownChains.isMainnet(31337))
    assertEquals("https://sepolia.etherscan.io/tx/0xab", KnownChains.txUrl(11155111, "0xab"))
  }
}
