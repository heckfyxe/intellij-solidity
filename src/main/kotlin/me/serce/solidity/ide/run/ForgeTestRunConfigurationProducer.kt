package me.serce.solidity.ide.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition

class ForgeTestRunConfigurationProducer : LazyRunConfigurationProducer<ForgeTestRunConfiguration>() {
  override fun getConfigurationFactory() = ForgeTestRunConfigurationFactory(ForgeTestRunConfigurationType())

  override fun isConfigurationFromContext(
    configuration: ForgeTestRunConfiguration,
    context: ConfigurationContext
  ): Boolean {
    return when (val element = context.location?.psiElement) {
      is SolFunctionDefinition -> {
        element.isForgeTest() &&
          configuration.testName == element.name &&
          configuration.contractName == element.contract?.name
      }

      is SolContractDefinition -> {
        configuration.contractName == element.name
      }

      else -> false
    }
  }

  override fun setupConfigurationFromContext(
    configuration: ForgeTestRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>
  ): Boolean {
    val element = context.location?.psiElement ?: return false
    val (contract, test) = when (element) {
      is SolFunctionDefinition -> if (element.isForgeTest()) element.contract?.name.orEmpty() to element.name.orEmpty() else return false
      is SolContractDefinition -> element.name.orEmpty() to ""
      else -> return false
    }
    configuration.name = listOf(contract, test).filter { it.isNotEmpty() }.joinToString(".")
    configuration.contractName = contract
    configuration.testName = test
    configuration.workingDirectory = forgeWorkingDirectory(element)
    sourceElement.set(element)
    return true
  }

  /** Runs the tests of [contract], or only [test] when given, from the Foundry project of [element]. */
  fun runTest(element: PsiElement, contract: String, test: String? = null) {
    val project = element.project
    val fullTestName = listOfNotNull(contract, test).joinToString(".")
    val configuration = ForgeTestRunConfiguration(project, configurationFactory, "Forge Test - $fullTestName")
    configuration.workingDirectory = forgeWorkingDirectory(element)
    configuration.contractName = contract
    configuration.testName = test ?: ""

    val runManager = RunManager.getInstance(project)
    val runnerAndConfigurationSettings = runManager.createConfiguration(configuration, configurationFactory)
    ProgramRunnerUtil.executeConfiguration(
      runnerAndConfigurationSettings,
      DefaultRunExecutor.getRunExecutorInstance()
    )
  }
}
