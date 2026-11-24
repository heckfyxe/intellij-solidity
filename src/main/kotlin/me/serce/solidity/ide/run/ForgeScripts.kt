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
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.ContractType
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.Visibility
import me.serce.solidity.lang.psi.findContract
import org.jdom.Element
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.EnvironmentUtil
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.KnownChains
import me.serce.solidity.chain.WalletConfig
import me.serce.solidity.chain.WalletKind
import me.serce.solidity.chain.wallet.CastWallet
import me.serce.solidity.chain.wallet.DefaultWalletPrompts
import java.nio.file.Path
import javax.swing.JLabel
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
  /** Where the script runs; [ForgeTargetKind.LOCAL] is a local in-memory chain. */
  val target = ForgeTarget()
  /** An RPC URL, or the name of an `[rpc_endpoints]` entry, for [ForgeTargetKind.URL]. */
  var rpcUrl: String
    get() = target.url
    set(value) {
      target.url = value
      if (value.isNotBlank() && target.kind == ForgeTargetKind.LOCAL) target.kind = ForgeTargetKind.URL
    }
  /** Sends the transactions: off by default, so a run is a simulation unless asked otherwise. */
  var broadcast = false
  /** On a network chain of the Chains tool window: the address of the chain's wallet that signs. Empty for its first. */
  var wallet = ""
  var additionalOptions = ""
  var workingDirectory = project.basePath ?: ""

  override fun getConfigurationEditor() = ForgeScriptRunConfigurationEditor(project)

  override fun getState(executor: Executor, environment: ExecutionEnvironment) = ForgeScriptCommandLineState(this, environment)

  override fun suggestedName() = listOf(contractName, signature.takeIf { it.isNotEmpty() && it != "run()" })
    .filterNotNull().filter { it.isNotEmpty() }.joinToString(".").ifEmpty { "Forge Script" }

  override fun checkConfiguration() {
    if (broadcast && target.kind == ForgeTargetKind.LOCAL) {
      throw RuntimeConfigurationError("Broadcasting needs a target: pick a network, a chain or an RPC URL")
    }
  }

  /** Stored as `<name value="..."/>` children. */
  private fun fields() = listOf(::scriptPath, ::contractName, ::signature, ::arguments, ::wallet, ::additionalOptions, ::workingDirectory)

  override fun readExternal(element: Element) {
    super.readExternal(element)
    fields().forEach { it.set(element.value(it.name)) }
    target.read(element, "rpc", legacyUrl = element.value("rpcUrl"))
    broadcast = element.value("broadcast") == "true"
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    fields().forEach { element.put(it.name, it.get()) }
    element.put("rpcUrl", target.url)
    target.write(element, "rpc")
    element.put("broadcast", broadcast.toString())
  }
}

/** Who signs a broadcast to a chain of the Chains tool window. */
sealed class ScriptSigner {
  abstract val address: String
  abstract fun args(): List<String>

  /** anvil, or a node of your own: it holds the keys and signs `eth_sendTransaction`. */
  class Unlocked(override val address: String) : ScriptSigner() {
    override fun args() = listOf("--unlocked", "--sender", address)
  }

  /** A wallet of a network chain; forge signs with it like cast does, so no key material enters the IDE. */
  class Wallet(val wallet: WalletConfig, val passwordFile: Path?) : ScriptSigner() {
    override val address get() = wallet.address
    override fun args(): List<String> = when (wallet.kind) {
      WalletKind.KEYSTORE -> listOf("--keystore", wallet.keystorePath) + (passwordFile?.let { listOf("--password-file", it.toString()) } ?: emptyList())
      WalletKind.LEDGER -> listOf("--ledger") + derivation()
      WalletKind.TREZOR -> listOf("--trezor") + derivation()
      WalletKind.BROWSER -> listOf("--browser")
    } + listOf("--sender", wallet.address)

    private fun derivation() =
      wallet.derivationPath.trim().takeIf { it.isNotEmpty() }?.let { listOf("--mnemonic-derivation-paths", it) } ?: emptyList()
  }
}

class ForgeScriptCommandLineState(private val configuration: ForgeScriptRunConfiguration, environment: ExecutionEnvironment) :
  CommandLineState(environment) {

  init {
    val project = configuration.project
    consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
      addFilter(SolidityFileLocationFilter(project, configuration.workingDirectory))
    }
  }

  @Throws(ExecutionException::class)
  override fun startProcess(): ProcessHandler {
    val project = configuration.project
    val target = configuration.target.resolve(project)
    val signer = if (configuration.broadcast && target != null) signer(target) else null
    try {
      if (configuration.broadcast && target != null) confirmBroadcast(target, signer)
      val handler = KillableColoredProcessHandler(generateCommandLine(target = target, signer = signer))
      ProcessTerminatedListener.attach(handler)
      handler.addProcessListener(object : ProcessListener {
        override fun processTerminated(event: ProcessEvent) {
          (signer as? ScriptSigner.Wallet)?.passwordFile?.let(CastWallet::deletePasswordFile)
          // balances, block number and code at deployed addresses changed
          if (configuration.broadcast) target?.chain?.let { ChainService.getInstance(project).refresh(it) }
        }
      })
      return handler
    } catch (e: Throwable) {
      (signer as? ScriptSigner.Wallet)?.passwordFile?.let(CastWallet::deletePasswordFile)
      throw e
    }
  }

  private fun signer(target: ResolvedTarget): ScriptSigner? {
    val chain = target.chain ?: return null // a network or URL: the user's own wallet options, if any
    val service = ChainService.getInstance(configuration.project)
    if (!chain.isNetworkChain) {
      return ScriptSigner.Unlocked(service.selectedAccount(chain.id) ?: throw ExecutionException("Chain \"${chain.name}\" has no accounts"))
    }
    val wallet = chain.wallets.firstOrNull { it.address.equals(configuration.wallet, ignoreCase = true) }
      ?: chain.wallets.firstOrNull()
      ?: throw ExecutionException("Chain \"${chain.name}\" has no wallets. Add one in the Chains tool window.")
    val password = if (wallet.kind == WalletKind.KEYSTORE) {
      DefaultWalletPrompts.password(configuration.project, wallet, null) ?: throw ExecutionException("Cancelled")
    } else null
    return ScriptSigner.Wallet(wallet, password?.let(CastWallet::writePasswordFile))
  }

  /** Anything but a local dev chain asks first, mainnets with a warning: the script can spend real funds. */
  private fun confirmBroadcast(target: ResolvedTarget, signer: ScriptSigner?) {
    val chain = target.chain
    if (chain != null && !chain.isNetworkChain) return
    if (target.chainId == 31337L || target.chainId == 1337L) return
    val mainnet = KnownChains.isMainnet(target.chainId)
    val script = configuration.contractName.ifEmpty { configuration.scriptPath } +
      (configuration.signature.takeIf { it.isNotBlank() && it != "run()" }?.let { ".$it" } ?: "")
    val message = buildString {
      append("Broadcast the transactions of $script to ${target.name} (chain id ${target.chainId ?: "unknown"})")
      append(signer?.let { " from ${it.address}" } ?: "")
      append("?")
      if (mainnet) append("\n\nThis is a real network: the transactions are paid with real funds.")
    }
    var ok = false
    ApplicationManager.getApplication().invokeAndWait({
      ok = Messages.showOkCancelDialog(
        configuration.project, message, if (mainnet) "Broadcast to ${target.name} (Real Funds)" else "Broadcast to ${target.name}",
        "Broadcast", Messages.getCancelButton(), if (mainnet) Messages.getWarningIcon() else Messages.getQuestionIcon(),
      ) == Messages.OK
    }, ModalityState.any())
    if (!ok) throw ExecutionException("Cancelled")
  }

  fun generateCommandLine(isWindows: Boolean = SystemInfo.isWindows, target: ResolvedTarget? = null, signer: ScriptSigner? = null): GeneralCommandLine {
    val forge = FoundryProject.forgePath(configuration.project, isWindows)
    val scriptTarget = configuration.scriptPath + (configuration.contractName.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
    val cmd = GeneralCommandLine().withExePath(forge).withParameters("script", scriptTarget)
    if (configuration.workingDirectory.isNotBlank()) cmd.withWorkDirectory(configuration.workingDirectory)
    configuration.signature.takeIf { it.isNotBlank() && it != "run()" }?.let { cmd.addParameters("--sig", it) }
    cmd.addParameters(ParametersListUtil.parse(configuration.arguments))
    if (target != null) target.applyTo(cmd, "--rpc-url")
    else if (configuration.target.kind == ForgeTargetKind.URL) configuration.rpcUrl.takeIf { it.isNotBlank() }?.let { cmd.addParameters("--rpc-url", it) }
    if (configuration.broadcast) cmd.addParameter("--broadcast")
    signer?.let { cmd.addParameters(it.args()) }
    cmd.addParameters(ParametersListUtil.parse(configuration.additionalOptions))
    if (signer != null) {
      // forge must sign with the wallet we pass, not one the user's environment names
      cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        .withEnvironment(EnvironmentUtil.getEnvironmentMap() - CastWallet.WALLET_ENV.toSet() - cmd.environment.keys)
    }
    return cmd.withEnvironment("NO_COLOR", "1")
  }
}

class ForgeScriptRunConfigurationEditor(private val project: Project) : SettingsEditor<ForgeScriptRunConfiguration>() {
  private val scriptPath = JBTextField()
  private val contractName = JBTextField()
  private val signature = JBTextField()
  private val arguments = RawCommandLineEditor()
  private val target = ForgeTargetEditor(project, "RPC URL or endpoint alias:")
  private val broadcast = JBCheckBox("Broadcast transactions (--broadcast)")
  private val wallet = ComboBox<WalletConfig>().apply {
    renderer = SimpleListCellRenderer.create("") { "${it.address}  ${it.label}" }
  }
  private val signerNote = JBLabel()
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
    .let(target::addTo)
    .addComponent(broadcast)
    .addLabeledComponent("Sign with:", wallet)
    .addComponent(signerNote)
    .addLabeledComponent("Additional options:", additionalOptions)
    .addLabeledComponent("Working directory:", workingDirectory)
    .addComponentFillVertically(JPanel(), 0)
    .panel

  init {
    target.onChange = ::updateSigner
    broadcast.addActionListener { updateSigner() }
  }

  private var savedWallet = ""

  private fun updateSigner() {
    val chain = target.selectedChain
    val wallets = chain?.takeIf { it.isNetworkChain }?.wallets.orEmpty()
    val previous = wallet.item?.address ?: savedWallet
    wallet.removeAllItems()
    wallets.forEach(wallet::addItem)
    wallet.item = wallets.firstOrNull { it.address.equals(previous, ignoreCase = true) } ?: wallets.firstOrNull()
    val showWallets = broadcast.isSelected && wallets.isNotEmpty()
    wallet.isVisible = showWallets
    panel.components.filterIsInstance<JLabel>().filter { it.labelFor === wallet }.forEach { it.isVisible = showWallets }
    signerNote.text = when {
      !broadcast.isSelected -> ""
      chain == null && target.kind.item != ForgeTargetKind.LOCAL -> "Signs with the wallet options you add below, e.g. --account or --ledger."
      chain != null && !chain.isNetworkChain -> "Signs with the chain's selected account (--unlocked)."
      chain != null && wallets.isEmpty() -> "This chain has no wallets: add one in the Chains tool window."
      else -> ""
    }
    signerNote.isVisible = signerNote.text.isNotEmpty()
  }

  override fun resetEditorFrom(c: ForgeScriptRunConfiguration) {
    scriptPath.text = c.scriptPath
    contractName.text = c.contractName
    signature.text = c.signature
    arguments.text = c.arguments
    target.reset(c.target)
    broadcast.isSelected = c.broadcast
    savedWallet = c.wallet
    wallet.item = null
    updateSigner()
    additionalOptions.text = c.additionalOptions
    workingDirectory.text = c.workingDirectory
  }

  override fun applyEditorTo(c: ForgeScriptRunConfiguration) {
    c.scriptPath = scriptPath.text
    c.contractName = contractName.text
    c.signature = signature.text
    c.arguments = arguments.text
    target.apply(c.target)
    c.broadcast = broadcast.isSelected
    c.wallet = wallet.item?.address ?: savedWallet
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
    val contract = element.findContract() ?: return null
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
