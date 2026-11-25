package me.serce.solidity.ide.run

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.serce.solidity.chain.foundry.FoundryProject

class ChiselRunConfigurationTest : BasePlatformTestCase() {
  private fun configuration() = ChiselRunConfiguration(project, ChiselRunConfigurationFactory(ChiselRunConfigurationType()), "Chisel")

  private fun state(configuration: ChiselRunConfiguration): ChiselCommandLineState {
    val settings = RunManager.getInstance(project).createConfiguration(configuration, configuration.factory!!)
    return ChiselCommandLineState(configuration, ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build())
  }

  fun testLocalSessionRunsChiselNextToForge() {
    val configuration = configuration().apply {
      workingDirectory = "/work/project"
      prelude = "script/Prelude.sol"
      additionalOptions = "--no-vm --use 0.8.30"
    }
    val cmd = state(configuration).generateCommandLine(isWindows = false)
    assertEquals(FoundryProject.toolPath(project, "chisel", isWindows = false), cmd.exePath)
    assertTrue(cmd.exePath.endsWith("chisel"))
    assertEquals(listOf("--prelude", "script/Prelude.sol", "--no-vm", "--use", "0.8.30"), cmd.parametersList.parameters)
    assertEquals(java.io.File("/work/project"), cmd.workDirectory)
    assertNull(cmd.environment[ResolvedTarget.RPC_ENV])
    assertEquals("xterm-256color", cmd.environment["TERM"])
    configuration.checkConfiguration()
  }

  fun testForkOfANetworkPassesTheUrlInTheEnvironment() {
    val configuration = configuration().apply {
      workingDirectory = myFixture.tempDirPath
      target.kind = ForgeTargetKind.NETWORK
      target.network = "Ethereum Mainnet"
      forkBlock = "19000000"
    }
    val target = ResolvedTarget("https://rpc.example/key", "Ethereum Mainnet", 1L, chain = null, onCommandLine = false)
    val cmd = state(configuration).generateCommandLine(isWindows = false, target = target)
    val parameters = cmd.parametersList.parameters
    assertFalse(parameters.any { it.contains("rpc.example") })
    assertEquals("https://rpc.example/key", cmd.environment[ResolvedTarget.RPC_ENV])
    assertEquals(listOf("--fork-block-number", "19000000"), parameters)
    configuration.checkConfiguration()
  }

  fun testTypedUrlGoesOnTheCommandLine() {
    val configuration = configuration().apply { workingDirectory = myFixture.tempDirPath }
    val target = ResolvedTarget("mainnet", "mainnet", null, chain = null, onCommandLine = true)
    val parameters = state(configuration).generateCommandLine(isWindows = false, target = target).parametersList.parameters
    assertEquals(listOf("--fork-url", "mainnet"), parameters)
  }

  fun testForkBlockNeedsATargetAndANumber() {
    val configuration = configuration().apply { forkBlock = "123" }
    assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
    configuration.target.kind = ForgeTargetKind.URL
    configuration.target.url = "mainnet"
    configuration.checkConfiguration()
    configuration.forkBlock = "latest"
    assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
  }

  fun testSettingsSurviveSaveAndLoad() {
    val configuration = configuration().apply {
      target.kind = ForgeTargetKind.CHAIN
      target.chain = "chain-1"
      forkBlock = "7"
      prelude = "p.sol"
      additionalOptions = "--no-vm"
      workingDirectory = "/w"
    }
    val element = org.jdom.Element("configuration")
    configuration.writeExternal(element)
    val loaded = configuration().apply { readExternal(element) }
    assertEquals(
      listOf(ForgeTargetKind.CHAIN, "chain-1", "7", "p.sol", "--no-vm", "/w"),
      listOf(loaded.target.kind, loaded.target.chain, loaded.forkBlock, loaded.prelude, loaded.additionalOptions, loaded.workingDirectory)
    )
  }

  /** Against the real chisel in ~/.foundry/bin: the pty session evaluates what is typed. Skipped without Foundry. */
  fun testSessionEvaluatesInput() {
    val bin = java.io.File(com.intellij.util.SystemProperties.getUserHome(), ".foundry/bin")
    if (!java.io.File(bin, "chisel").canExecute()) return
    val settings = me.serce.solidity.settings.SoliditySettings.getInstance(project)
    val saved = me.serce.solidity.settings.SoliditySettingsState().also { it.copyFrom(settings.state) }
    try {
      settings.testFoundryConfigurationMode = me.serce.solidity.settings.ConfigurationMode.MANUAL
      settings.testFoundryExecutablePath = java.io.File(bin, "forge").path
      val configuration = configuration().apply { workingDirectory = com.intellij.openapi.util.io.FileUtil.createTempDirectory("chisel", null).path }
      val handler = state(configuration).startProcess()
      val output = StringBuffer()
      handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
        override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
          output.append(event.text)
        }
      })
      handler.startNotify()
      val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
      fun text() = output.toString().replace(ansi, "")
      fun waitFor(what: String) {
        val deadline = System.currentTimeMillis() + 120_000
        while (what !in text() && System.currentTimeMillis() < deadline && !handler.isProcessTerminated) Thread.sleep(200)
      }
      fun type(line: String) = handler.processInput!!.apply { write("$line\r".toByteArray()); flush() }
      waitFor("Welcome to Chisel")
      type("uint x = 40 + 2;")
      Thread.sleep(3000)
      type("x")
      waitFor("Decimal: 42")
      handler.destroyProcess()
      assertTrue(text(), "Decimal: 42" in text())
    } finally {
      settings.loadState(saved)
    }
  }
}
