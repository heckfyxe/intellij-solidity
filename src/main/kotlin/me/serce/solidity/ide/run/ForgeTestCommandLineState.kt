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
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.ConfigurationMode
import me.serce.solidity.settings.SoliditySettings

/** [failedTests] (contract to test function names) limits the run to those tests, for "Rerun Failed Tests". */
class ForgeTestCommandLineState(
  private val configuration: ForgeTestRunConfiguration,
  environment: ExecutionEnvironment,
  private val failedTests: Map<String, Set<String>>? = null,
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
    // both are regexes: anchor them so that `testFoo` doesn't also run `testFooBar`;
    // tests are matched against their signature, `testFoo(uint256)`
    val tests = failedTests?.values?.flatten()?.toSet() ?: setOfNotNull(configuration.testName.ifBlank { null })
    val contracts = failedTests?.keys ?: setOfNotNull(configuration.contractName.ifBlank { null })
    if (tests.isNotEmpty()) cmd.addParameters("--match-test", "^${alternatives(tests)}\\(")
    if (contracts.isNotEmpty()) cmd.addParameters("--match-contract", "^${alternatives(contracts)}$")
    return cmd
  }

  @Throws(ExecutionException::class)
  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val properties = ForgeTestConsoleProperties(configuration, executor)
    val console = SMTestRunnerConnectionUtil.createConsole("Foundry", properties)
    val processHandler = startProcess()
    val root = (processHandler as? ForgeTestTransformingProcessHandler)?.workDirectory?.path
      ?: configuration.workingDirectory.ifBlank { configuration.project.basePath ?: "" }
    console.addMessageFilter(SolidityFileLocationFilter(configuration.project, LocalFileSystem.getInstance().findFileByPath(root)))
    console.attachToProcess(processHandler)
    return DefaultExecutionResult(console, processHandler, *createActions(console, processHandler))
  }
}

private fun alternatives(names: Set<String>): String =
  names.toList().let { // test and contract names are identifiers: nothing to escape
   if (it.size == 1) it.single() else it.joinToString("|", "(", ")") }

class ForgeTestConsoleProperties(configuration: ForgeTestRunConfiguration, executor: Executor) :
  SMTRunnerConsoleProperties(configuration, "Foundry", executor) {
  override fun getTestLocator() = ForgeTestLocator

  override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
    ForgeRerunFailedTestsAction(consoleView, this)
}

/** Reruns the failed tests by name: `--match-contract ^(A|B)$ --match-test ^(f|g)\(`. */
private class ForgeRerunFailedTestsAction(consoleView: ConsoleView, properties: ForgeTestConsoleProperties) :
  AbstractRerunFailedTestsAction(consoleView) {
  init {
    init(properties)
  }

  override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
    val configuration = myConsoleProperties.configuration as? ForgeTestRunConfiguration ?: return null
    val failed = getFailedTests(configuration.project)
      .filter { it.isLeaf }
      .mapNotNull { test -> test.parent?.name?.let { it to test.name.substringBefore('(') } }
      .groupBy({ it.first }, { it.second })
      .mapValues { it.value.toSet() }
    if (failed.isEmpty()) return null
    return object : MyRunProfile(configuration) {
      override fun getModules() = emptyArray<com.intellij.openapi.module.Module>()
      override fun getState(executor: Executor, env: ExecutionEnvironment) = ForgeTestCommandLineState(configuration, env, failed)
    }
  }
}
