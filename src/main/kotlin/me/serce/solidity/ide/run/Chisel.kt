package me.serce.solidity.ide.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.foundry.FoundryProject
import org.jdom.Element
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * chisel, Foundry's Solidity REPL, run in a terminal console (it reads keys and draws its prompt itself, so it needs a
 * pty). It runs in the Foundry root, where it picks up the project's remappings, and forks the run configuration's
 * target like `forge test` does: the URL goes in `FOUNDRY_ETH_RPC_URL`, which chisel reads too.
 */
class ChiselRunConfigurationType : ConfigurationType {
  override fun getId() = ID
  override fun getIcon() = AllIcons.Nodes.Console
  override fun getDisplayName() = "Chisel"
  override fun getConfigurationTypeDescription() = "Opens chisel, Foundry's Solidity REPL"
  override fun getConfigurationFactories() = arrayOf(ChiselRunConfigurationFactory(this))

  companion object {
    const val ID = "ChiselRunConfigurationType"
  }
}

class ChiselRunConfigurationFactory(type: ChiselRunConfigurationType) : ConfigurationFactory(type) {
  override fun getId() = "ChiselRunConfigurationFactory"
  override fun createTemplateConfiguration(project: Project): RunConfiguration = ChiselRunConfiguration(project, this, "Chisel")
  override fun isApplicable(project: Project) = Registry.`is`("solidity.forge.enabled", true)
}

class ChiselRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  RunConfigurationBase<CommandLineState>(project, factory, name) {

  /** The chain chisel forks; [ForgeTargetKind.LOCAL] is a fresh in-memory chain. */
  val target = ForgeTarget()
  /** `--fork-block-number`, empty for the latest block. */
  var forkBlock = ""
  /** `--prelude`: a Solidity file, or a directory of them, evaluated before the session starts. */
  var prelude = ""
  var additionalOptions = ""
  /** Where chisel runs; empty for the project's Foundry root. */
  var workingDirectory = ""

  override fun getConfigurationEditor() = ChiselRunConfigurationEditor(project)

  override fun getState(executor: Executor, environment: ExecutionEnvironment) = ChiselCommandLineState(this, environment)

  override fun checkConfiguration() {
    if (forkBlock.isNotBlank()) {
      if (forkBlock.trim().toULongOrNull() == null) throw RuntimeConfigurationError("The fork block must be a block number")
      if (target.kind == ForgeTargetKind.LOCAL) throw RuntimeConfigurationError("A fork block needs a target: pick a network, a chain or an RPC URL")
    }
  }

  /** The directory chisel runs in. */
  fun effectiveWorkingDirectory(): String =
    workingDirectory.ifBlank { FoundryProject.find(project)?.root?.toString() ?: project.basePath.orEmpty() }

  private fun fields() = listOf(::forkBlock, ::prelude, ::additionalOptions, ::workingDirectory)

  override fun readExternal(element: Element) {
    super.readExternal(element)
    fields().forEach { it.set(element.value(it.name)) }
    target.read(element, "fork", legacyUrl = element.value("forkUrl"))
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    fields().forEach { element.put(it.name, it.get()) }
    element.put("forkUrl", target.url)
    target.write(element, "fork")
  }
}

class ChiselCommandLineState(private val configuration: ChiselRunConfiguration, environment: ExecutionEnvironment) :
  CommandLineState(environment) {

  @Throws(ExecutionException::class)
  public override fun startProcess(): ProcessHandler {
    val cmd = generateCommandLine(target = configuration.target.resolve(configuration.project))
    if (!File(cmd.exePath).canExecute()) {
      throw ExecutionException("chisel not found at ${cmd.exePath}. Install Foundry with foundryup or set the forge path in Settings | Solidity")
    }
    val handler = KillableProcessHandler(PtyCommandLine(cmd))
    ProcessTerminatedListener.attach(handler)
    return handler
  }

  /** A terminal, not the usual console: chisel edits its input line with escape sequences. */
  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    val console = TerminalExecutionConsole(configuration.project, handler)
    return DefaultExecutionResult(console, handler)
  }

  fun generateCommandLine(isWindows: Boolean = SystemInfo.isWindows, target: ResolvedTarget? = null): GeneralCommandLine {
    val chisel = FoundryProject.toolPath(configuration.project, "chisel", isWindows)
    val cmd = GeneralCommandLine(chisel).withCharset(Charsets.UTF_8)
    val workDir = configuration.effectiveWorkingDirectory()
    if (workDir.isNotBlank()) cmd.withWorkDirectory(workDir)
    target?.applyTo(cmd, "--fork-url")
    configuration.forkBlock.trim().takeIf { it.isNotEmpty() && target != null }?.let { cmd.addParameters("--fork-block-number", it) }
    configuration.prelude.trim().takeIf { it.isNotEmpty() }?.let { cmd.addParameters("--prelude", it) }
    cmd.addParameters(ParametersListUtil.parse(configuration.additionalOptions))
    // colours and line editing need a terminal type; the console emulates xterm
    return cmd.withEnvironment("TERM", "xterm-256color")
  }
}

class ChiselRunConfigurationEditor(project: Project) : SettingsEditor<ChiselRunConfiguration>() {
  private val target = ForgeTargetEditor(project, "Fork URL or endpoint alias:")
  private val forkBlock = JBTextField().apply { emptyText.text = "latest" }
  private val prelude = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(
      "Select Prelude", "A Solidity file, or a directory of them, evaluated before the session", project,
      FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
    )
  }
  private val additionalOptions = RawCommandLineEditor()
  private val workingDirectory = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(
      "Select Working Directory", "The Foundry project directory", project,
      FileChooserDescriptorFactory.createSingleFolderDescriptor()
    )
    textField.let { (it as? com.intellij.ui.components.JBTextField)?.emptyText?.text = "Foundry root" }
  }

  private val panel: JPanel = FormBuilder.createFormBuilder()
    .let(target::addTo)
    .addLabeledComponent("Fork block:", forkBlock)
    .addLabeledComponent("Prelude:", prelude)
    .addLabeledComponent("Additional options:", additionalOptions)
    .addLabeledComponent("Working directory:", workingDirectory)
    .addComponentFillVertically(JPanel(), 0)
    .panel

  override fun resetEditorFrom(c: ChiselRunConfiguration) {
    target.reset(c.target)
    forkBlock.text = c.forkBlock
    prelude.text = c.prelude
    additionalOptions.text = c.additionalOptions
    workingDirectory.text = c.workingDirectory
  }

  override fun applyEditorTo(c: ChiselRunConfiguration) {
    target.apply(c.target)
    c.forkBlock = forkBlock.text.trim()
    c.prelude = prelude.text.trim()
    c.additionalOptions = additionalOptions.text
    c.workingDirectory = workingDirectory.text.trim()
  }

  override fun createEditor(): JComponent = panel
}

object Chisel {
  /**
   * Runs chisel on [chain] (a fork of it), or on a local in-memory chain. Reuses the run configuration of an earlier
   * call, so its settings (prelude, options) can be edited and stay.
   */
  fun open(project: Project, chain: ChainConfig? = null) {
    val name = chain?.let { "Chisel on ${it.name}" } ?: "Chisel"
    val runManager = RunManager.getInstance(project)
    val type = ChiselRunConfigurationType()
    val settings = runManager.findConfigurationByTypeAndName(ChiselRunConfigurationType.ID, name)
      ?: runManager.createConfiguration(name, type.configurationFactories.single()).also { created ->
        val configuration = created.configuration as ChiselRunConfiguration
        if (chain != null) {
          configuration.target.kind = ForgeTargetKind.CHAIN
          configuration.target.chain = chain.id
        }
        runManager.addConfiguration(created)
      }
    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
  }
}

/** Tools | Open Chisel: a REPL on a local in-memory chain, in the project's Foundry root. */
class OpenChiselAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && Registry.`is`("solidity.forge.enabled", true)
  }

  override fun actionPerformed(e: AnActionEvent) {
    Chisel.open(e.project ?: return)
  }
}
