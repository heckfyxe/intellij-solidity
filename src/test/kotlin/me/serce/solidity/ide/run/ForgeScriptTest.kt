package me.serce.solidity.ide.run

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.utils.SolLightPlatformCodeInsightFixtureTestCase
import com.intellij.psi.util.PsiTreeUtil

class ForgeScriptTest : SolLightPlatformCodeInsightFixtureTestCase() {

  private val script = """
    abstract contract Script {}
    abstract contract Base is Script { function run() public virtual; }
    contract Deploy is Script {
        function setUp() public {}
        function run() public {}
        function deploy(address owner, uint amount) external {}
        function helper() internal {}
    }
    library L { function run() public {} }
  """.trimIndent()

  private fun functions() = PsiTreeUtil.findChildrenOfType(myFixture.file, SolFunctionDefinition::class.java)
    .associateBy { "${it.contract?.name}.${it.name}" }

  private fun contracts() = PsiTreeUtil.findChildrenOfType(myFixture.file, SolContractDefinition::class.java)
    .associateBy { it.name }

  fun testScriptEntries() {
    myFixture.configureByText("Deploy.s.sol", script)
    val fns = functions()
    assertTrue(fns.getValue("Deploy.run").isForgeScriptEntry())
    assertTrue(fns.getValue("Deploy.setUp").isForgeScriptEntry())
    assertTrue(fns.getValue("Deploy.deploy").isForgeScriptEntry())
    assertFalse(fns.getValue("Deploy.helper").isForgeScriptEntry())
    assertFalse(fns.getValue("Base.run").isForgeScriptEntry())
    assertFalse(fns.getValue("L.run").isForgeScriptEntry())
    assertEquals("deploy(address,uint256)", fns.getValue("Deploy.deploy").scriptSignature())
    assertFalse(contracts().getValue("Base").isForgeScript())
    assertTrue(contracts().getValue("Deploy").isForgeScript())
  }

  fun testNotAScriptFile() {
    myFixture.configureByText("Deploy.sol", script)
    assertFalse(functions().getValue("Deploy.run").isForgeScriptEntry())
  }

  fun testCommandLine() {
    val factory = ForgeScriptRunConfigurationFactory(ForgeScriptRunConfigurationType())
    val configuration = ForgeScriptRunConfiguration(project, factory, "s")
    configuration.workingDirectory = "/work"
    configuration.scriptPath = "script/Deploy.s.sol"
    configuration.contractName = "Deploy"
    configuration.signature = "deploy(address,uint256)"
    configuration.arguments = "0x0000000000000000000000000000000000000001 5"
    configuration.rpcUrl = "sepolia"
    configuration.broadcast = true
    configuration.additionalOptions = "-vvvv --slow"
    val settings = RunManager.getInstance(project).createConfiguration(configuration, factory)
    val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()

    val cmd = ForgeScriptCommandLineState(configuration, env).generateCommandLine(false)

    assertEquals(
      listOf(
        "script", "script/Deploy.s.sol:Deploy", "--sig", "deploy(address,uint256)",
        "0x0000000000000000000000000000000000000001", "5", "--rpc-url", "sepolia", "--broadcast", "-vvvv", "--slow"
      ),
      cmd.parametersList.list
    )
    assertEquals("/work", cmd.workDirectory.path)
  }

  fun testRunIsTheDefaultAndNothingIsBroadcast() {
    val factory = ForgeScriptRunConfigurationFactory(ForgeScriptRunConfigurationType())
    val configuration = ForgeScriptRunConfiguration(project, factory, "s")
    configuration.scriptPath = "script/Deploy.s.sol"
    configuration.signature = "run()"
    val settings = RunManager.getInstance(project).createConfiguration(configuration, factory)
    val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()

    val cmd = ForgeScriptCommandLineState(configuration, env).generateCommandLine(false)

    assertEquals(listOf("script", "script/Deploy.s.sol"), cmd.parametersList.list)
  }

  private fun configuration(): Pair<ForgeScriptRunConfiguration, com.intellij.execution.runners.ExecutionEnvironment> {
    val factory = ForgeScriptRunConfigurationFactory(ForgeScriptRunConfigurationType())
    val configuration = ForgeScriptRunConfiguration(project, factory, "s")
    configuration.workingDirectory = "/work"
    configuration.scriptPath = "script/Deploy.s.sol"
    val settings = RunManager.getInstance(project).createConfiguration(configuration, factory)
    return configuration to ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
  }

  fun testNetworkUrlGoesInTheEnvironmentNotTheCommandLine() {
    val (configuration, env) = configuration()
    configuration.target.kind = ForgeTargetKind.NETWORK
    configuration.target.network = "Sepolia"
    val target = ResolvedTarget("https://rpc.example/secret-key", "Sepolia", 11155111L, chain = null, onCommandLine = false)

    val cmd = ForgeScriptCommandLineState(configuration, env).generateCommandLine(false, target)

    assertEquals(listOf("script", "script/Deploy.s.sol"), cmd.parametersList.list)
    assertEquals("https://rpc.example/secret-key", cmd.environment[ResolvedTarget.RPC_ENV])
  }

  fun testKeystoreSignerOfANetworkChain() {
    val (configuration, env) = configuration()
    configuration.broadcast = true
    val wallet = me.serce.solidity.chain.WalletConfig().apply {
      kind = me.serce.solidity.chain.WalletKind.KEYSTORE
      keystorePath = "/keys/dev"
      address = "0x0000000000000000000000000000000000000001"
    }
    val target = ResolvedTarget("https://rpc.example/k", "Sepolia", 11155111L, chain = null, onCommandLine = false)
    val signer = ScriptSigner.Wallet(wallet, java.nio.file.Path.of("/tmp/p"))

    val cmd = ForgeScriptCommandLineState(configuration, env).generateCommandLine(false, target, signer)

    assertEquals(
      listOf("script", "script/Deploy.s.sol", "--broadcast", "--keystore", "/keys/dev", "--password-file", "/tmp/p",
        "--sender", "0x0000000000000000000000000000000000000001"),
      cmd.parametersList.list
    )
    // forge must not sign with a wallet the user's environment names
    assertEquals(com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.NONE, cmd.parentEnvironmentType)
    assertFalse(me.serce.solidity.chain.wallet.CastWallet.WALLET_ENV.any { it in cmd.environment })
    assertEquals("https://rpc.example/k", cmd.environment[ResolvedTarget.RPC_ENV])
  }

  fun testUnlockedSignerOfALocalChain() {
    val (configuration, env) = configuration()
    configuration.broadcast = true
    val target = ResolvedTarget("http://127.0.0.1:8545", "Local chain", 31337L, chain = null, onCommandLine = false)
    val cmd = ForgeScriptCommandLineState(configuration, env)
      .generateCommandLine(false, target, ScriptSigner.Unlocked("0x00000000000000000000000000000000000000aa"))
    assertEquals(
      listOf("script", "script/Deploy.s.sol", "--broadcast", "--unlocked", "--sender", "0x00000000000000000000000000000000000000aa"),
      cmd.parametersList.list
    )
  }

  fun testTargetIsSavedAndOldRpcUrlLoadsAsUrlTarget() {
    val (configuration, _) = configuration()
    configuration.target.kind = ForgeTargetKind.CHAIN
    configuration.target.chain = "chain-id"
    configuration.wallet = "0x0000000000000000000000000000000000000002"
    val element = org.jdom.Element("configuration")
    configuration.writeExternal(element)
    val (loaded, _) = configuration()
    loaded.readExternal(element)
    assertEquals(ForgeTargetKind.CHAIN, loaded.target.kind)
    assertEquals("chain-id", loaded.target.chain)
    assertEquals("0x0000000000000000000000000000000000000002", loaded.wallet)

    val legacy = org.jdom.Element("configuration")
    legacy.addContent(org.jdom.Element("rpcUrl").setAttribute("value", "sepolia"))
    val (old, _) = configuration()
    old.readExternal(legacy)
    assertEquals(ForgeTargetKind.URL, old.target.kind)
    assertEquals("sepolia", old.rpcUrl)
  }

  fun testBroadcastNeedsATarget() {
    val (configuration, _) = configuration()
    configuration.broadcast = true
    assertThrows(com.intellij.execution.configurations.RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
  }

  fun testChainTargetMustBeRunning() {
    val chain = me.serce.solidity.chain.ChainConfig().apply { name = "Dev" }
    me.serce.solidity.chain.ChainService.getInstance(project).addChain(chain)
    try {
      val target = ForgeTarget().apply { kind = ForgeTargetKind.CHAIN; this.chain = chain.id }
      try {
        target.resolve(project)
        fail("a stopped chain must not resolve")
      } catch (e: com.intellij.execution.ExecutionException) {
        assertTrue(e.message!!.contains("not running"))
      }
    } finally {
      me.serce.solidity.chain.ChainService.getInstance(project).removeChain(chain)
    }
  }

  fun testGutterOnRunOnly() {
    myFixture.configureByText("Deploy.s.sol", script)
    val contributor = ForgeScriptRunLineMarkerContributor()
    val marked = PsiTreeUtil.collectElements(myFixture.file) { contributor.getInfo(it) != null }
      .map { it.text + "@" + (it.parent as? SolFunctionDefinition)?.contract?.name }
    assertEquals(listOf("Deploy@null", "run@Deploy"), marked)
  }
}
