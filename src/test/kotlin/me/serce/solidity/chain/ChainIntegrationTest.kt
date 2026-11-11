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
    val historyBefore = service.history().size // tests share the project, and with it the history

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

    assertEquals(historyBefore + 5, service.history().size)
  }

  /** Every failure says why and where, including reverts without a reason such as decoding a call to an empty address. */
  fun testFailuresShowReasonAndLocation() {
    if (!File(bin, "anvil").canExecute() || !File(bin, "forge").canExecute()) return
    val root = FileUtil.createTempDirectory("chain-errors", null)
    File(root, "foundry.toml").writeText("[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
    File(root, "src").mkdirs()
    val source = """
      // SPDX-License-Identifier: MIT
      pragma solidity ^0.8.20;
      interface IMeaning { function meaningOfLifeAndAllExistence() external returns (uint256); }
      contract Checked {
          error Negative(int256 value);
          function check(uint256 x) public pure returns (uint256) {
              require(x > 0, "x must be positive");
              return x;
          }
      }
      contract Example {
          function askTheMeaningOfLife(address source) public returns (uint256) {
              (bool ok, bytes memory result) = source.call(abi.encodeWithSignature("meaningOfLifeAndAllExistence()"));
              require(ok, "call failed");
              return abi.decode(result, (uint256));
          }
          function askViaInterface(address source) public returns (uint256) {
              return IMeaning(source).meaningOfLifeAndAllExistence();
          }
          function askChecked(Checked c) public returns (uint256) {
              return c.check(0);
          }
          function divide(uint256 a, uint256 b) public pure returns (uint256) {
              return a / b;
          }
      }
      contract Broken {
          constructor() { revert("cannot deploy"); }
      }
    """.trimIndent()
    File(root, "src/Example.sol").writeText(source)
    fun line(text: String) = "src/Example.sol:" + (source.lines().indexOfFirst { it.contains(text) } + 1)

    val foundry = FoundryProject(root.toPath(), File(bin, "forge").path)
    val build = foundry.build()
    assertTrue(build.messages.joinToString { it.formatted }, build.success)
    val artifacts = foundry.artifacts().associateBy { it.name }

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
    val executor = ChainExecutor(service) { foundry }

    val example = executor.deploy(chain, artifacts.getValue("Example"), emptyList(), from, BigInteger.ZERO).deployment!!
    val checked = executor.deploy(chain, artifacts.getValue("Checked"), emptyList(), from, BigInteger.ZERO).deployment!!
    val fns = example.abi().functions.associateBy { it.name }
    val nobody = "0x4B20993Bc481177ec7E8f571ceCaE8A9e22C02db"
    fun call(name: String, vararg args: Any) = executor.invoke(chain, example, fns.getValue(name), args.toList(), from, BigInteger.ZERO).record

    // the tutorial's case: the low-level call to an empty address succeeds, decoding its empty result reverts
    val decoded = call("askTheMeaningOfLife", nobody)
    assertEquals(TxStatus.REVERTED, decoded.status)
    assertEquals("reverted without a reason", decoded.error)
    assertEquals(line("return abi.decode"), decoded.errorFrames.first().label)
    assertEquals("Example", decoded.errorFrames.first().contract)
    assertTrue(decoded.errorFrames.first().code, decoded.errorFrames.first().code.contains("abi.decode(result, (uint256))"))
    assertTrue(decoded.notes.toString(), decoded.notes.any { it.startsWith("No contract at $nobody") })

    // a high-level call checks for code first and reverts without calling
    val viaInterface = call("askViaInterface", nobody)
    assertEquals("reverted without a reason", viaInterface.error)
    assertEquals(line("IMeaning(source)"), viaInterface.errorFrames.first().label)
    assertTrue(viaInterface.notes.toString(), viaInterface.notes.any { it.startsWith("No contract at $nobody") })

    // a revert inside another contract shows where it started and the call that led there
    val nested = call("askChecked", checked.address)
    assertEquals("x must be positive", nested.error)
    assertEquals(listOf(line("require(x > 0"), line("return c.check(0)")), nested.errorFrames.map { it.label })
    assertEquals(listOf("Checked", "Example"), nested.errorFrames.map { it.contract })

    val division = call("divide", BigInteger.ONE, BigInteger.ZERO)
    assertEquals("panic 0x12: division or modulo by zero", division.error)
    assertEquals(line("return a / b"), division.errorFrames.first().label)

    val broken = executor.deploy(chain, artifacts.getValue("Broken"), emptyList(), from, BigInteger.ZERO).record
    assertEquals(TxStatus.REVERTED, broken.status)
    assertEquals("cannot deploy", broken.error)
    assertEquals(line("revert(\"cannot deploy\")"), broken.errorFrames.first().label)

    // a deployment remembered from a chain that was restarted since
    val gone = Deployment().apply { chainId = chain.id; name = "Example"; address = nobody; abiJson = example.abiJson }
    val stale = executor.invoke(chain, gone, fns.getValue("divide"), listOf(BigInteger.ONE, BigInteger.ONE), from, BigInteger.ZERO).record
    assertEquals(TxStatus.FAILED, stale.status)
    assertTrue(stale.error, stale.error.startsWith("There is no contract at $nobody"))
  }
}
