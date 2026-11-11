package me.serce.solidity.ide.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.WriteExternalException
import com.intellij.util.xmlb.Constants
import me.serce.solidity.settings.SoliditySettings
import org.jdom.Element

class ForgeTestRunConfiguration(
  project: Project,
  factory: ForgeTestRunConfigurationFactory,
  name: String
) : LocatableConfigurationBase<ForgeTestCommandLineState>(project, factory, name) {

  var contractName: String = ""
  var testName: String = ""
  var workingDirectory: String = SoliditySettings.getInstance(project).testFoundryConfigPath.ifBlank {
    project.basePath ?: ""
  }

  override fun getConfigurationEditor() = ForgeTestRunConfigurationEditor()

  override fun getState(executor: Executor, environment: ExecutionEnvironment) =
    ForgeTestCommandLineState(this, environment)

  @Throws(InvalidDataException::class)
  override fun readExternal(element: Element) {
    super.readExternal(element)
    testName = element.readValue("testName")
    contractName = element.readValue("contractName")
    workingDirectory = element.readValue("workingDirectory")
  }

  @Throws(WriteExternalException::class)
  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    element.writeValue("testName", testName)
    element.writeValue("contractName", contractName)
    element.writeValue("workingDirectory", workingDirectory)
  }

  override fun suggestedName(): String = "Forge Test${if (testName.isNotEmpty()) " - $testName" else ""}"
}

private fun Element.readValue(name: String): String = getChild(name)?.getAttributeValue(Constants.VALUE) ?: ""

private fun Element.writeValue(name: String, value: String) {
  addContent(Element(name).setAttribute(Constants.VALUE, value))
}
