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
    val location = context.location ?: return false
    val element = location.psiElement

    return when (element) {
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
    val location = context.location ?: return false
    val element = location.psiElement
    val project = context.project

    when (element) {
      is SolFunctionDefinition -> {
        if (element.isForgeTest()) {
          configuration.name = "${element.contract?.name}.${element.name}"
          configuration.testName = element.name ?: ""
          configuration.contractName = element.contract?.name ?: ""
          configuration.workingDirectory = forgeWorkingDirectory(element)
          sourceElement.set(element)
          return true
        }
      }

      is SolContractDefinition -> {
        configuration.name = element.name ?: ""
        configuration.contractName = element.name ?: ""
        configuration.workingDirectory = forgeWorkingDirectory(element)
        sourceElement.set(element)
        return true
      }
    }

    return false
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
