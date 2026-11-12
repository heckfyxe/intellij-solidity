package me.serce.solidity.ide.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.Constants
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.ContractType
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.Visibility
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.SoliditySettings
import org.jdom.Element
import javax.swing.JComponent
import javax.swing.JPanel

/** A `*.s.sol` file, or a Solidity file in the `script` directory of its Foundry project. */
fun isForgeScriptFile(file: PsiFile): Boolean {
  if (file.name.endsWith(".s.sol")) return true
  val vf = file.virtualFile ?: return false
  val root = FoundryProject.rootOf(vf) ?: return false
  return VfsUtilCore.getRelativePath(vf, root)?.startsWith("script/") == true
}

/** A deployable contract of a script file: forge runs its `run()`, or another function given with `--sig`. */
fun SolContractDefinition.isForgeScript(): Boolean =
  !isAbstract && contractType == ContractType.COMMON && isForgeScriptFile(containingFile)

/** A function `forge script --sig` can call: public or external, in a script contract. */
fun SolFunctionDefinition.isForgeScriptEntry(): Boolean {
  if (name.isNullOrEmpty() || isConstructor || specialFunction != null) return false
  if (visibility != Visibility.PUBLIC && visibility != Visibility.EXTERNAL) return false
  return contract?.isForgeScript() == true
}

/** The `--sig` of [this]: `run()`, `deploy(address,uint256)`. */
fun SolFunctionDefinition.scriptSignature(): String =
  "$name(" + parameters.joinToString(",") { it.typeName.text.replace(Regex("\\s+"), "") } + ")"

/** Where forge runs for [element], and the script's path relative to it. */
private fun scriptLocation(element: PsiElement): Pair<String, String>? {
  val vf = element.containingFile?.virtualFile ?: return null
  val root = FoundryProject.rootOf(vf) ?: return null
  return root.path to (VfsUtilCore.getRelativePath(vf, root) ?: return null)
}

class ForgeScriptRunConfigurationType : ConfigurationType {
  override fun getId() = "ForgeScriptRunConfigurationType"
  override fun getIcon() = SolidityIcons.FILE_ICON
  override fun getDisplayName() = "Forge Script"
  override fun getConfigurationTypeDescription() = "Runs a Solidity script with forge script"
  override fun getConfigurationFactories() = arrayOf(ForgeScriptRunConfigurationFactory(this))
}

class ForgeScriptRunConfigurationFactory(type: ForgeScriptRunConfigurationType) : ConfigurationFactory(type) {
  override fun getId() = "ForgeScriptRunConfigurationFactory"
  override fun createTemplateConfiguration(project: Project): RunConfiguration =
    ForgeScriptRunConfiguration(project, this, "Forge Script")
  override fun isApplicable(project: Project) = Registry.`is`("solidity.forge.enabled", true)
}

class ForgeScriptRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  LocatableConfigurationBase<CommandLineState>(project, factory, name) {

  /** The script file, relative to [workingDirectory]. */
  var scriptPath = ""
  var contractName = ""
  /** The function to run, `run()` when empty. */
  var signature = ""
  /** The function's arguments, as forge expects them after the script path. */
  var arguments = ""
  /** An RPC URL, or the name of an `[rpc_endpoints]` entry; empty runs on a local in-memory chain. */
  var rpcUrl = ""
  /** Sends the transactions: off by default, so a run is a simulation unless asked otherwise. */
  var broadcast = false
  var additionalOptions = ""
  var workingDirectory = project.basePath ?: ""

  override fun getConfigurationEditor() = ForgeScriptRunConfigurationEditor()

  override fun getState(executor: Executor, environment: ExecutionEnvironment) = ForgeScriptCommandLineState(this, environment)

  override fun suggestedName() = listOf(contractName, signature.takeIf { it.isNotEmpty() && it != "run()" })
    .filterNotNull().filter { it.isNotEmpty() }.joinToString(".").ifEmpty { "Forge Script" }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    scriptPath = element.value("scriptPath")
    contractName = element.value("contractName")
    signature = element.value("signature")
    arguments = element.value("arguments")
    rpcUrl = element.value("rpcUrl")
    broadcast = element.value("broadcast") == "true"
    additionalOptions = element.value("additionalOptions")
    workingDirectory = element.value("workingDirectory")
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    element.put("scriptPath", scriptPath)
    element.put("contractName", contractName)
    element.put("signature", signature)
    element.put("arguments", arguments)
    element.put("rpcUrl", rpcUrl)
    element.put("broadcast", broadcast.toString())
    element.put("additionalOptions", additionalOptions)
    element.put("workingDirectory", workingDirectory)
  }

  private fun Element.value(name: String) = getChild(name)?.getAttributeValue(Constants.VALUE) ?: ""
  private fun Element.put(name: String, value: String) {
    addContent(Element(name).setAttribute(Constants.VALUE, value))
  }
}

class ForgeScriptCommandLineState(private val configuration: ForgeScriptRunConfiguration, environment: ExecutionEnvironment) :
  CommandLineState(environment) {

  init {
    val project = configuration.project
    val root = LocalFileSystem.getInstance().findFileByPath(configuration.workingDirectory)
    consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
      addFilter(SolidityFileLocationFilter(project, root))
    }
  }

  @Throws(ExecutionException::class)
  override fun startProcess(): ProcessHandler {
    val handler = KillableColoredProcessHandler(generateCommandLine())
    ProcessTerminatedListener.attach(handler)
    return handler
  }

  fun generateCommandLine(isWindows: Boolean = SystemInfo.isWindows): GeneralCommandLine {
    val settings = SoliditySettings.getInstance(configuration.project)
    val forge = resolveForgeExecutable(settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, isWindows)
    val target = configuration.scriptPath + (configuration.contractName.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
    val cmd = GeneralCommandLine().withExePath(forge).withParameters("script", target)
    if (configuration.workingDirectory.isNotBlank()) cmd.withWorkDirectory(configuration.workingDirectory)
    configuration.signature.takeIf { it.isNotBlank() && it != "run()" }?.let { cmd.addParameters("--sig", it) }
    cmd.addParameters(ParametersListUtil.parse(configuration.arguments))
    configuration.rpcUrl.takeIf { it.isNotBlank() }?.let { cmd.addParameters("--rpc-url", it) }
    if (configuration.broadcast) cmd.addParameter("--broadcast")
    cmd.addParameters(ParametersListUtil.parse(configuration.additionalOptions))
    return cmd.withEnvironment("NO_COLOR", "1")
  }
}

class ForgeScriptRunConfigurationEditor : SettingsEditor<ForgeScriptRunConfiguration>() {
  private val scriptPath = JBTextField()
  private val contractName = JBTextField()
  private val signature = JBTextField()
  private val arguments = RawCommandLineEditor()
  private val rpcUrl = JBTextField()
  private val broadcast = JBCheckBox("Broadcast transactions (--broadcast)")
  private val additionalOptions = RawCommandLineEditor()
  private val workingDirectory = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(
      "Select Working Directory", "The Foundry project directory", null,
      FileChooserDescriptorFactory.createSingleFolderDescriptor()
    )
  }

  private val panel: JPanel = FormBuilder.createFormBuilder()
    .addLabeledComponent("Script path:", scriptPath)
    .addLabeledComponent("Contract (when the file has several):", contractName)
    .addLabeledComponent("Function signature (empty for run()):", signature)
    .addLabeledComponent("Function arguments:", arguments)
    .addLabeledComponent("RPC URL or endpoint name (empty for a local simulation):", rpcUrl)
    .addComponent(broadcast)
    .addLabeledComponent("Additional options:", additionalOptions)
    .addLabeledComponent("Working directory:", workingDirectory)
    .addComponentFillVertically(JPanel(), 0)
    .panel

  override fun resetEditorFrom(c: ForgeScriptRunConfiguration) {
    scriptPath.text = c.scriptPath
    contractName.text = c.contractName
    signature.text = c.signature
    arguments.text = c.arguments
    rpcUrl.text = c.rpcUrl
    broadcast.isSelected = c.broadcast
    additionalOptions.text = c.additionalOptions
    workingDirectory.text = c.workingDirectory
  }

  override fun applyEditorTo(c: ForgeScriptRunConfiguration) {
    c.scriptPath = scriptPath.text
    c.contractName = contractName.text
    c.signature = signature.text
    c.arguments = arguments.text
    c.rpcUrl = rpcUrl.text
    c.broadcast = broadcast.isSelected
    c.additionalOptions = additionalOptions.text
    c.workingDirectory = workingDirectory.text
  }

  override fun createEditor(): JComponent = panel
}

/** Creates `forge script` configurations from a script contract (runs `run()`) or one of its functions (`--sig`). */
class ForgeScriptRunConfigurationProducer : LazyRunConfigurationProducer<ForgeScriptRunConfiguration>() {
  override fun getConfigurationFactory() = ForgeScriptRunConfigurationFactory(ForgeScriptRunConfigurationType())

  private fun target(context: ConfigurationContext): Pair<SolContractDefinition, SolFunctionDefinition?>? {
    val element = context.location?.psiElement ?: return null
    val function = PsiTreeUtil.getParentOfType(element, SolFunctionDefinition::class.java, false)
    if (function != null && function.isForgeScriptEntry()) return function.contract!! to function
    val contract = PsiTreeUtil.getParentOfType(element, SolContractDefinition::class.java, false) ?: return null
    // inside another member of the contract, e.g. setUp or a state variable: not a script target
    if (function != null) return null
    return if (contract.isForgeScript()) contract to null else null
  }

  override fun setupConfigurationFromContext(
    configuration: ForgeScriptRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>
  ): Boolean {
    val (contract, function) = target(context) ?: return false
    val (root, path) = scriptLocation(contract) ?: return false
    configuration.workingDirectory = root
    configuration.scriptPath = path
    configuration.contractName = contract.name ?: return false
    configuration.signature = function?.scriptSignature()?.takeIf { it != "run()" } ?: ""
    configuration.name = configuration.suggestedName()
    sourceElement.set(function ?: contract)
    return true
  }

  override fun isConfigurationFromContext(configuration: ForgeScriptRunConfiguration, context: ConfigurationContext): Boolean {
    val (contract, function) = target(context) ?: return false
    val (root, path) = scriptLocation(contract) ?: return false
    val signature = function?.scriptSignature()?.takeIf { it != "run()" } ?: ""
    return configuration.workingDirectory == root && configuration.scriptPath == path &&
      configuration.contractName == contract.name && configuration.signature.trim().takeIf { it != "run()" }.orEmpty() == signature
  }
}

/** A run icon on script contracts and their `run` functions, with the usual Run/Debug/Modify menu. */
class ForgeScriptRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.node?.elementType != SolidityTokenTypes.IDENTIFIER) return null
    val show = when (val parent = element.parent) {
      is SolContractDefinition -> parent.identifier == element && parent.isForgeScript()
      is SolFunctionDefinition -> parent.identifier == element && parent.name?.startsWith("run") == true && parent.isForgeScriptEntry()
      else -> false
    }
    if (!show) return null
    return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run forge script" }
  }
}
