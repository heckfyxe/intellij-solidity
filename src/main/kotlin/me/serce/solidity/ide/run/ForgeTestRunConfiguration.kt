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
  /** Extra `forge test` arguments, split like a shell command line. */
  var additionalArguments: String = ""
  /** `FOUNDRY_PROFILE`, the foundry.toml profile to run with; empty for the default. */
  var profile: String = ""
  /** `--fork-url`: an RPC URL or an `[rpc_endpoints]` alias. */
  var forkUrl: String = ""
  /** `--fuzz-seed`, to reproduce a fuzz run. */
  var fuzzSeed: String = ""
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
    additionalArguments = element.readValue("additionalArguments")
    profile = element.readValue("profile")
    forkUrl = element.readValue("forkUrl")
    fuzzSeed = element.readValue("fuzzSeed")
  }

  @Throws(WriteExternalException::class)
  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    element.writeValue("testName", testName)
    element.writeValue("contractName", contractName)
    element.writeValue("workingDirectory", workingDirectory)
    element.writeValue("additionalArguments", additionalArguments)
    element.writeValue("profile", profile)
    element.writeValue("forkUrl", forkUrl)
    element.writeValue("fuzzSeed", fuzzSeed)
  }

  override fun checkConfiguration() {
    if (fuzzSeed.isNotBlank() && !FUZZ_SEED.matches(fuzzSeed.trim())) {
      throw com.intellij.execution.configurations.RuntimeConfigurationError("Fuzz seed must be a decimal or 0x-prefixed hex number")
    }
  }

  override fun suggestedName(): String = "Forge Test${if (testName.isNotEmpty()) " - $testName" else ""}"
}

private val FUZZ_SEED = Regex("^(0[xX][0-9a-fA-F]+|[0-9]+)$")

private fun Element.readValue(name: String): String = getChild(name)?.getAttributeValue(Constants.VALUE) ?: ""

private fun Element.writeValue(name: String, value: String) {
  addContent(Element(name).setAttribute(Constants.VALUE, value))
}
