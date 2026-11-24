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
  /** What the tests fork: nothing, a network, a chain of the Chains tool window, or a URL. */
  val fork = ForgeTarget()
  /** `--fork-url` for [ForgeTargetKind.URL]: an RPC URL or an `[rpc_endpoints]` alias. */
  var forkUrl: String
    get() = fork.url
    set(value) {
      fork.url = value
      if (value.isNotBlank() && fork.kind == ForgeTargetKind.LOCAL) fork.kind = ForgeTargetKind.URL
    }
  /** `--fork-block-number`; empty for the latest block. */
  var forkBlock: String = ""
  /** `--fuzz-seed`, to reproduce a fuzz run. */
  var fuzzSeed: String = ""
  var workingDirectory: String = SoliditySettings.getInstance(project).testFoundryConfigPath.ifBlank {
    project.basePath ?: ""
  }

  override fun getConfigurationEditor() = ForgeTestRunConfigurationEditor(project)

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
    fork.read(element, "fork", legacyUrl = element.readValue("forkUrl"))
    forkBlock = element.readValue("forkBlock")
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
    fork.write(element, "fork")
    element.writeValue("forkBlock", forkBlock)
    element.writeValue("fuzzSeed", fuzzSeed)
  }

  override fun checkConfiguration() {
    if (fuzzSeed.isNotBlank() && !FUZZ_SEED.matches(fuzzSeed.trim())) {
      throw com.intellij.execution.configurations.RuntimeConfigurationError("Fuzz seed must be a decimal or 0x-prefixed hex number")
    }
    if (forkBlock.isNotBlank() && !FORK_BLOCK.matches(forkBlock.trim())) {
      throw com.intellij.execution.configurations.RuntimeConfigurationError("Fork block must be a block number")
    }
    if (forkBlock.isNotBlank() && fork.kind == ForgeTargetKind.LOCAL) {
      throw com.intellij.execution.configurations.RuntimeConfigurationError("A fork block needs something to fork: pick a target")
    }
  }

  override fun suggestedName(): String = "Forge Test${if (testName.isNotEmpty()) " - $testName" else ""}"
}

private val FORK_BLOCK = Regex("^[0-9]+$")
private val FUZZ_SEED = Regex("^(0[xX][0-9a-fA-F]+|[0-9]+)$")

private fun Element.readValue(name: String): String = getChild(name)?.getAttributeValue(Constants.VALUE) ?: ""

private fun Element.writeValue(name: String, value: String) {
  addContent(Element(name).setAttribute(Constants.VALUE, value))
}
