package me.serce.solidity.chain

import me.serce.solidity.util.hexToBytes
import me.serce.solidity.util.checksumAddress
import me.serce.solidity.util.withoutHexPrefix
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SystemProperties
import com.intellij.util.ui.UIUtil
import me.serce.solidity.chain.abi.AbiText
import me.serce.solidity.chain.abi.AbiFetcher
import me.serce.solidity.chain.rpc.JsonRpcClient
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.wallet.CastException
import me.serce.solidity.chain.wallet.CastWallet
import me.serce.solidity.chain.wallet.TxSummary
import me.serce.solidity.chain.wallet.WalletPrompts
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

  private class FakePrompts(var answer: WalletPrompts.Confirmation = WalletPrompts.Confirmation.SEND, val passwords: ArrayDeque<String>) : WalletPrompts {
    val confirmed = ArrayList<TxSummary>()
    val passwordErrors = ArrayList<String?>()
    override fun confirm(project: Project, summary: TxSummary): WalletPrompts.Confirmation {
      confirmed += summary
      return answer
    }

    override fun password(project: Project, wallet: WalletConfig, error: String?): String? {
      passwordErrors += error
      return passwords.removeFirstOrNull()
    }
  }

  fun testKeystoreWalletDeploysAndTransacts() {
    if (!File(bin, "anvil").canExecute() || !File(bin, "cast").canExecute()) return
    val rpc = startNetwork("Keystore net")
    val dir = FileUtil.createTempDirectory("keystores", null)
    val created = GeneralCommandLine(File(bin, "cast").path, "wallet", "new", dir.path, "--unsafe-password", "pw").let {
      CapturingProcessHandler(it).runProcess(60_000)
    }
    assertEquals(created.stderr, 0, created.exitCode)
    val keystore = dir.listFiles()!!.single()
    val cast = CastWallet(File(bin, "cast").path)
    val wallet = WalletConfig().apply { kind = WalletKind.KEYSTORE; keystorePath = keystore.path }
    wallet.address = cast.address(wallet, "pw")
    assertTrue(created.stdout, created.stdout.contains(wallet.address))
    try {
      cast.address(wallet, "wrong")
      fail("wrong password accepted")
    } catch (e: CastException) {
      assertTrue(e.message, e.wrongPassword)
    }
    rpc.call("anvil_setBalance", wallet.address, "0xDE0B6B3A7640000")

    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { kind = ChainKind.NETWORK; network = "Keystore net"; name = "Keystore net"; wallets = arrayListOf(wallet) }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 30))
    assertEquals(listOf(wallet.address), rt.accounts)

    val prompts = FakePrompts(passwords = ArrayDeque(listOf("wrong", "pw", "pw", "pw")))
    val executor = ChainExecutor(service, prompts = prompts, cast = { File(bin, "cast").path })
    val artifact = Artifact("Answer", "", """[{"type":"function","name":"poke","inputs":[],"outputs":[],"stateMutability":"nonpayable"}]""",
      "0x600a600c600039600a6000f3602a60005260206000f3", hasLinkReferences = false)
    val deployed = executor.deploy(chain, artifact, emptyList(), wallet.address, BigInteger.ZERO)
    assertEquals(deployed.record.error, TxStatus.SUCCESS, deployed.record.status)
    assertEquals(listOf(null, "Wrong password"), prompts.passwordErrors)
    val summary = prompts.confirmed.single()
    assertNull(summary.to)
    assertFalse(summary.mainnet)
    assertEquals(11155111L, summary.chainId)
    assertNotNull(summary.estimatedFee)

    val d = deployed.deployment!!
    val poke = d.abi().functions.single()
    prompts.answer = WalletPrompts.Confirmation.CANCEL
    val cancelled = executor.invoke(chain, d, poke, emptyList(), wallet.address, BigInteger.ZERO)
    assertEquals(TxStatus.FAILED, cancelled.record.status)
    assertEquals("Not sent: cancelled", cancelled.record.error)
    assertEquals(BigInteger.ONE, rpc.call("eth_getTransactionCount", wallet.address, "latest").asText().withoutHexPrefix().toBigInteger(16))

    prompts.answer = WalletPrompts.Confirmation.SEND_AND_STOP_ASKING
    val sent = executor.invoke(chain, d, poke, emptyList(), wallet.address, BigInteger.ZERO)
    assertEquals(sent.record.error, TxStatus.SUCCESS, sent.record.status)
    assertTrue(sent.record.txHash.startsWith("0x"))
    assertEquals("Answer ${d.address}", prompts.confirmed.last().to)
    val confirmations = prompts.confirmed.size
    val again = executor.invoke(chain, d, poke, emptyList(), wallet.address, BigInteger.ZERO)
    assertEquals(again.record.error, TxStatus.SUCCESS, again.record.status)
    assertEquals(confirmations, prompts.confirmed.size) // a test network stops asking when told to
    service.stop(chain)
  }

  fun testFindsProxyImplementations() {
    if (!File(bin, "anvil").canExecute()) return
    val rpc = startNetwork("Proxy net")
    val from = rpc.accounts().first()
    val impl = "0x2222222222222222222222222222222222222222"
    val proxy = "0x1111111111111111111111111111111111111111"
    assertNull(AbiFetcher.proxyImplementation(rpc, proxy))
    rpc.call("anvil_setStorageAt", proxy, AbiFetcher.IMPLEMENTATION_SLOT, "0x" + "0".repeat(24) + impl.withoutHexPrefix())
    assertEquals(impl, AbiFetcher.proxyImplementation(rpc, proxy))
    // a beacon proxy: the beacon's implementation() returns 42, read as an address
    val hash = rpc.sendTransaction(from, null, "0x600a600c600039600a6000f3602a60005260206000f3".hexToBytes(), BigInteger.ZERO)
    val beacon = rpc.waitForReceipt(hash)["contractAddress"].asText()
    val beaconProxy = "0x3333333333333333333333333333333333333333"
    rpc.call("anvil_setStorageAt", beaconProxy, AbiFetcher.BEACON_SLOT, "0x" + "0".repeat(24) + beacon.withoutHexPrefix())
    assertEquals("0x000000000000000000000000000000000000002A", AbiFetcher.proxyImplementation(rpc, beaconProxy)!!.uppercase().replace("0X", "0x"))
  }

  fun testGeneratedInterfaceDeclaresStructs() {
    if (!File(bin, "cast").canExecute()) return
    val abi = AbiText.withStructNames(AbiText.parse("function place(tuple(address maker, uint96 amount)[] orders) returns (uint256)").json, "IBook")
    val file = FileUtil.createTempFile("abi", ".json").apply { writeText(abi) }
    val out = CapturingProcessHandler(GeneralCommandLine(File(bin, "cast").path, "interface", file.path, "-n", "IBook")).runProcess(60_000)
    assertEquals(out.stderr, 0, out.exitCode)
    assertTrue(out.stdout, out.stdout.contains("struct PlaceOrders"))
    assertFalse(out.stderr, out.stderr.contains("Failed to format"))
  }

  fun testLowLevelCallsAndTransfers() {
    if (!File(bin, "anvil").canExecute()) return
    val rpc = startNetwork("Low-level net")
    val accounts = rpc.accounts()
    val hash = rpc.sendTransaction(accounts[0], null, "0x600a600c600039600a6000f3602a60005260206000f3".hexToBytes(), BigInteger.ZERO)
    val answer = checksumAddress(rpc.waitForReceipt(hash)["contractAddress"].asText())
    val service = ChainService.getInstance(project)
    // a node that unlocks its accounts, so sending needs no wallet
    val chain = ChainConfig().apply { kind = ChainKind.EXTERNAL; url = rpc.url; name = "node" }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 30))
    val executor = ChainExecutor(service)

    val call = executor.lowLevel(chain, answer, "0xdeadbeef".hexToBytes(), null, BigInteger.ZERO, send = false)
    assertEquals(call.record.error, TxStatus.SUCCESS, call.record.status)
    assertEquals("call 0xdeadbeef", call.record.function)
    assertEquals("returned: 0x" + "0".repeat(62) + "2a", call.record.results.single())

    val to = checksumAddress(accounts[1])
    val before = rpc.balance(to)
    val transfer = executor.lowLevel(chain, to, ByteArray(0), rt.accounts.first(), BigInteger.TEN, send = true)
    assertEquals(transfer.record.error, TxStatus.SUCCESS, transfer.record.status)
    assertEquals("send ETH", transfer.record.function)
    assertEquals(before + BigInteger.TEN, rpc.balance(to))
    assertEquals(TxStatus.FAILED, executor.lowLevel(chain, to, ByteArray(0), null, BigInteger.ONE, send = true).record.status)
    service.stop(chain)
  }

  fun testCastErrorMessage() {
    assertEquals("Failed to decrypt keystore \"/k\" Mac Mismatch",
      CastWallet.errorMessage("warning: x\nError: Failed to decrypt keystore \"/k\"\n\nContext:\n- Mac Mismatch\n"))
  }

  fun testMainnetDetection() {
    assertTrue(KnownChains.isMainnet(1))
    assertFalse(KnownChains.isMainnet(11155111))
    assertTrue(KnownChains.isMainnet(987654321)) // unknown: treated as real money
    assertFalse(KnownChains.isMainnet(31337))
    assertEquals("https://sepolia.etherscan.io/tx/0xab", KnownChains.txUrl(11155111, "0xab"))
  }
}
