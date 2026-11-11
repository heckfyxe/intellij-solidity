package me.serce.solidity.ide.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.util.SystemInfo
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.ConfigurationMode
import me.serce.solidity.settings.SoliditySettings

class ForgeTestCommandLineState(
  private val configuration: ForgeTestRunConfiguration,
  environment: ExecutionEnvironment,
) : CommandLineState(environment) {

  @Throws(ExecutionException::class)
  public override fun startProcess(): ProcessHandler = ForgeTestTransformingProcessHandler(generateCommandLine())

  fun generateCommandLine(isWindows: Boolean = SystemInfo.isWindows): GeneralCommandLine {
    val settings = SoliditySettings.getInstance(configuration.project)
    val foundryExePath =
      resolveForgeExecutable(settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, isWindows)
    val cmd = GeneralCommandLine()
      .withExePath(foundryExePath)
      .withParameters("test", "-vvvv")

    if (configuration.workingDirectory.isNotBlank()) {
      cmd.withWorkDirectory(configuration.workingDirectory)
    } else if (settings.testFoundryConfigPath.isNotBlank() && settings.testFoundryConfigurationMode == ConfigurationMode.MANUAL) {
      cmd.addParameters("--root", settings.testFoundryConfigPath)
    }
    if (configuration.testName.isNotBlank()) {
      cmd.addParameters("--match-test", configuration.testName)
    }
    if (configuration.contractName.isNotBlank()) {
      cmd.addParameters("--match-contract", configuration.contractName)
    }
    return cmd
  }

  @Throws(ExecutionException::class)
  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val properties = SMTRunnerConsoleProperties(configuration, "xUnit", executor)
    val console = SMTestRunnerConnectionUtil.createConsole("Foundry", properties)
    val processHandler = startProcess()
    console.attachToProcess(processHandler)
    return DefaultExecutionResult(console, processHandler, *createActions(console, processHandler))
  }
}
