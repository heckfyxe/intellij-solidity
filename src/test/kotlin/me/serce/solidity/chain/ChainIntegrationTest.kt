package me.serce.solidity.chain

import com.intellij.execution.configurations.GeneralCommandLine
import me.serce.solidity.chain.ui.ChainUi
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SystemProperties
import com.intellij.util.ui.UIUtil
import me.serce.solidity.chain.abi.AbiValueParser
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.rpc.JsonRpcClient
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket

/**
 * End-to-end against a real anvil and forge: build, deploy, call, transact, revert and events.
 * Skipped when Foundry is not installed.
 */
class ChainIntegrationTest : BasePlatformTestCase() {
  private val bin = File(SystemProperties.getUserHome(), ".foundry/bin")
  private var anvil: Process? = null

  override fun tearDown() {
    try {
      anvil?.destroy()
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
    return rt.status
  }

  /** The preferred port is taken by something else: anvil must get another port and we must talk to it, not the squatter. */
  fun testStartsOnFreePortWhenPreferredIsTaken() {
    if (!File(bin, "anvil").canExecute()) return
    ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { squatter ->
      val service = ChainService.getInstance(project)
      val chain = ChainConfig().apply { name = "local"; port = squatter.localPort; persistent = false }
      service.addChain(chain)
      service.start(chain)
      val rt = service.runtime(chain.id)
      assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 30))
      assertFalse(rt.rpc!!.url.endsWith(":${squatter.localPort}"))
      assertEquals(31337L, rt.chainId)
      assertEquals(10, rt.accounts.size)
      service.stop(chain)
      rt.process?.waitFor(10_000)
    }
  }

  fun testForkFailureShowsAnvilsReason() {
    if (!File(bin, "anvil").canExecute()) return
    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { name = "bad fork"; forkUrl = "http://127.0.0.1:1"; persistent = false; port = 8790 }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(ChainStatus.FAILED, waitFor(rt, 60))
    assertTrue(rt.error, rt.error!!.startsWith("anvil stopped:") || rt.error!!.contains("listening"))
  }

  /** Dev accounts with code on the forked network (e.g. EIP-7702 sweepers on mainnet) become plain accounts on the fork. */
  fun testForkClearsCodeOfDevAccounts() {
    if (!File(bin, "anvil").canExecute()) return
    val upstreamPort = ServerSocket(0).use { it.localPort }
    anvil = GeneralCommandLine(File(bin, "anvil").path, "--port", upstreamPort.toString()).createProcess()
    val upstream = JsonRpcClient("http://127.0.0.1:$upstreamPort")
    for (i in 1..50) if (runCatching { upstream.chainId() }.isSuccess) break else Thread.sleep(200)
    val swept = upstream.accounts().first()
    upstream.call("anvil_setCode", swept, "0xef01008a67b5020ee254ef48e3b6a04927f39baf7e408a")
    assertTrue(upstream.code(swept).isNotEmpty())

    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { name = "fork"; forkUrl = upstream.url; persistent = false; port = 8791 }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 60))
    rt.accounts.forEach { assertTrue(it, rt.rpc!!.code(it).isEmpty()) }
    service.stop(chain)
    rt.process?.waitFor(10_000)
  }

  /** The fork RPC dropped the fork block's state (as non-archive nodes do): a transaction fails with why, not hangs. */
  fun testStaleForkExplainsFailure() {
    if (!File(bin, "anvil").canExecute()) return
    val upstreamPort = ServerSocket(0).use { it.localPort }
    // nobody reads its log, which would fill the pipe and block anvil while it mines many blocks
    anvil = ProcessBuilder(File(bin, "anvil").path, "--port", upstreamPort.toString(), "--prune-history", "1")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val upstream = JsonRpcClient("http://127.0.0.1:$upstreamPort")
    for (i in 1..50) if (runCatching { upstream.chainId() }.isSuccess) break else Thread.sleep(200)

    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { name = "fork"; forkUrl = upstream.url; persistent = false; port = 8792 }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 60))
    val from = rt.accounts.first()
    val executor = ChainExecutor(service)
    // a contract whose code is a single STOP, so any call succeeds
    val artifact = Artifact("Stop", "src/Stop.sol", """[{"type":"function","name":"poke","inputs":[],"outputs":[],"stateMutability":"payable"}]""",
      "0x6001600c60003960016000f300", hasLinkReferences = false)
    val deployed = executor.deploy(chain, artifact, emptyList(), from, BigInteger.ZERO)
    assertEquals(deployed.record.error, TxStatus.SUCCESS, deployed.record.status)
    val poke = deployed.deployment!!.abi().functions.single()

    upstream.call("anvil_mine", "0x300") // the fork block's state is pruned upstream now
    val stale = executor.invoke(chain, deployed.deployment!!, poke, emptyList(), from, BigInteger.ONE)
    assertEquals(TxStatus.FAILED, stale.record.status)
    assertTrue(stale.record.error, stale.record.error.startsWith("The fork RPC no longer serves the state of the fork block"))
    assertTrue(rt.staleForkNotified)
    service.stop(chain)
    rt.process?.waitFor(10_000)
  }

  /** Deploying builds what the editor shows, not the last saved file. */
  fun testBuildSavesEditorsFirst() {
    if (!File(bin, "forge").canExecute()) return
    val root = FileUtil.createTempDirectory("chain-save", null)
    File(root, "foundry.toml").writeText("[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
    File(root, "src").mkdirs()
    val source = File(root, "src/Box.sol").apply {
      writeText("// SPDX-License-Identifier: MIT\npragma solidity ^0.8.20;\ncontract Box { function a() public {} }\n")
    }
    val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(source)!!
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText(document.text.replace("function a() public {}", "function a() public {} function b() public {}"))
    }
    assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

    var built: List<Artifact>? = null
    ChainUi.buildThen(project, FoundryProject(root.toPath(), File(bin, "forge").path)) { built = it }
    val deadline = System.currentTimeMillis() + 120_000
    while (built == null && System.currentTimeMillis() < deadline) {
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(100)
    }
    val box = built!!.single { it.name == "Box" }
    assertEquals(listOf("a", "b"), box.abi.functions.map { it.name }.sorted())
  }

  fun testDeployCallTransact() {
    if (!File(bin, "anvil").canExecute() || !File(bin, "forge").canExecute()) return
    val root = FileUtil.createTempDirectory("chain-it", null)
    File(root, "foundry.toml").writeText("[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
    File(root, "src").mkdirs()
    File(root, "src/Bank.sol").writeText(
      """
      // SPDX-License-Identifier: MIT
      pragma solidity ^0.8.20;
      contract Bank {
          error TooSmall(uint256 amount);
          event Deposited(address indexed who, uint256 amount);
          mapping(address => uint256) public balances;
          string public name;
          constructor(string memory n) { name = n; }
          function deposit() public payable returns (uint256) {
              if (msg.value < 1 ether) revert TooSmall(msg.value);
              balances[msg.sender] += msg.value;
              emit Deposited(msg.sender, msg.value);
              return balances[msg.sender];
          }
          function withdraw() public { require(balances[msg.sender] > 0, "nothing to withdraw"); }
      }
      """.trimIndent()
    )
    val foundry = FoundryProject(root.toPath(), File(bin, "forge").path)
    val build = foundry.build()
    assertTrue(build.messages.joinToString { it.formatted }, build.success)
    val artifact = foundry.artifacts().single { it.name == "Bank" }
    assertTrue(foundry.isSource(artifact))

    val port = ServerSocket(0).use { it.localPort }
    anvil = GeneralCommandLine(File(bin, "anvil").path, "--port", port.toString()).createProcess()
    val rpc = JsonRpcClient("http://127.0.0.1:$port")
    for (i in 1..50) if (runCatching { rpc.chainId() }.isSuccess) break else Thread.sleep(200)

    val service = ChainService.getInstance(project)
    val chain = ChainConfig().apply { kind = ChainKind.EXTERNAL; url = rpc.url; name = "test" }
    service.addChain(chain)
    service.start(chain)
    val rt = service.runtime(chain.id)
    assertEquals(rt.error, ChainStatus.RUNNING, waitFor(rt, 30))
    val from = rt.accounts.first()
    val executor = ChainExecutor(service)

    val deployed = executor.deploy(chain, artifact, listOf("piggy"), from, BigInteger.ZERO)
    assertEquals(deployed.record.error, TxStatus.SUCCESS, deployed.record.status)
    val bank = deployed.deployment!!
    val fns = bank.abi().functions.associateBy { it.name }

    val name = executor.invoke(chain, bank, fns.getValue("name"), emptyList(), from, BigInteger.ZERO)
    assertEquals(listOf("string piggy"), name.record.results)

    val oneEth = AbiValueParser.parseAmount("1.5 eth")!!
    val deposit = executor.invoke(chain, bank, fns.getValue("deposit"), emptyList(), from, oneEth)
    assertEquals(deposit.record.error, TxStatus.SUCCESS, deposit.record.status)
    assertEquals(TxKind.TRANSACT, deposit.record.kind)
    assertTrue(deposit.record.results.single(), deposit.record.results.single().startsWith("uint256 1500000000000000000"))
    assertEquals(listOf("Deposited(who: $from, amount: 1500000000000000000)"), deposit.record.events)
    assertTrue(deposit.record.gasUsed.isNotEmpty())

    val small = executor.invoke(chain, bank, fns.getValue("deposit"), emptyList(), from, BigInteger.ONE)
    assertEquals(TxStatus.REVERTED, small.record.status)
    assertEquals("TooSmall(amount: 1)", small.record.error)

    val other = rt.accounts[1]
    val withdraw = executor.invoke(chain, bank, fns.getValue("withdraw"), emptyList(), other, BigInteger.ZERO)
    assertEquals(TxStatus.REVERTED, withdraw.record.status)
    assertEquals("nothing to withdraw", withdraw.record.error)

    assertEquals(5, service.history().size)
  }
}
