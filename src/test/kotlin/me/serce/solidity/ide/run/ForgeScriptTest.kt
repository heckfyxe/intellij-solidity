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
        function deploy(address owner, uint256 amount) external {}
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

  fun testGutterOnRunOnly() {
    myFixture.configureByText("Deploy.s.sol", script)
    val contributor = ForgeScriptRunLineMarkerContributor()
    val marked = PsiTreeUtil.collectElements(myFixture.file) { contributor.getInfo(it) != null }
      .map { it.text + "@" + (it.parent as? SolFunctionDefinition)?.contract?.name }
    assertEquals(listOf("Deploy@null", "run@Deploy"), marked)
  }
}
