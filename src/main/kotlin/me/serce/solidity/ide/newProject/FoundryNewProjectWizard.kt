package me.serce.solidity.ide.newProject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import me.serce.solidity.chain.chainNotification
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.ConfigurationMode
import me.serce.solidity.settings.SoliditySettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** File | New | Project | Foundry: creates the project and runs `forge init` in it. */
class FoundryNewProjectWizard : GeneratorNewProjectWizard {
  override val id = "solidity.foundry"
  override val name = "Foundry"
  override val icon = SolidityIcons.FILE_ICON
  override val description = "A Solidity project created with forge init"

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context).nextStep(::NewProjectWizardBaseStep).nextStep(::FoundryStep)
}

/** What `forge init` is asked to do. */
data class ForgeInitOptions(val git: Boolean, val empty: Boolean, val template: String) {
  /** `--force` because the IDE has already created `.idea` in the directory. */
  fun command(forge: String): List<String> = buildList {
    addAll(listOf(forge, "init", "--force"))
    if (!git) add("--no-git")
    if (empty) add("--empty")
    if (template.isNotBlank()) addAll(listOf("--template", template.trim()))
    add(".")
  }
}

private class FoundryStep(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
  private val defaultForge = defaultForgePath()
  private val forgeProperty = propertyGraph.property(defaultForge)
  private val gitProperty = propertyGraph.property(true)
  private val emptyProperty = propertyGraph.property(false)
  private val templateProperty = propertyGraph.property("")

  override fun setupUI(builder: Panel) {
    with(builder) {
      row("forge:") {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select forge"))
          .bindText(forgeProperty)
          .align(AlignX.FILL)
          .validationOnApply { if (File(it.text.trim()).canExecute()) null else error("forge not found. Install Foundry with foundryup.") }
      }
      row("Template:") {
        textField().bindText(templateProperty).align(AlignX.FILL)
          .comment("A GitHub repository to start from, such as foundry-rs/forge-template. Leave empty for the default.")
      }
      row { checkBox("Create Git repository").bindSelected(gitProperty) }
      row {
        checkBox("Empty project").bindSelected(emptyProperty)
          .comment("Without the sample Counter contract, test and script")
      }
    }
  }

  override fun setupProject(project: Project) {
    val dir = Path.of(base.path, base.name)
    Files.createDirectories(dir)
    GeneralModuleType.INSTANCE.createModuleBuilder().apply {
      name = base.name
      contentEntryPath = dir.toString()
      moduleFilePath = dir.resolve(".idea").resolve("${base.name}.iml").toString()
    }.commit(project)

    val forge = forgeProperty.get().trim()
    if (forge != defaultForge) {
      SoliditySettings.getInstance(project).apply {
        testFoundryConfigurationMode = ConfigurationMode.MANUAL
        testFoundryExecutablePath = forge
      }
    }
    val options = ForgeInitOptions(gitProperty.get(), emptyProperty.get(), templateProperty.get())
    StartupManager.getInstance(project).runAfterOpened { forgeInit(project, dir, forge, options) }
  }
}

private fun defaultForgePath(): String {
  val settings = SoliditySettings.getInstance(ProjectManager.getInstance().defaultProject)
  return resolveForgeExecutable(settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, SystemInfo.isWindows)
}

/** Runs `forge init` in the background (it clones forge-std, so it takes a few seconds), then shows the result. */
private fun forgeInit(project: Project, dir: Path, forge: String, options: ForgeInitOptions) {
  object : Task.Backgroundable(project, "Running forge init", false) {
    override fun run(indicator: ProgressIndicator) {
      val command = GeneralCommandLine(options.command(forge)).withWorkDirectory(dir.toFile()).withEnvironment("NO_COLOR", "1")
      val output = CapturingProcessHandler(command).runProcess(10 * 60_000)
      if (output.exitCode != 0 || output.isTimeout) {
        refresh(dir)
        val text = (output.stderr.ifBlank { output.stdout }).trim().ifEmpty { "exit code ${output.exitCode}" }
        chainNotification("forge init failed", text, NotificationType.ERROR).notify(project)
        return
      }
      ensureGitignore(dir)
      refresh(dir)
      val sample = listOf("src/Counter.sol", "README.md").firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByNioFile(dir.resolve(it)) }
      if (sample != null) invokeLater { if (!project.isDisposed) FileEditorManager.getInstance(project).openFile(sample, true) }
    }
  }.queue()
}

private fun refresh(dir: Path) {
  LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
}

/** What `forge init` writes to `.gitignore`; it writes nothing with `--no-git` or `--empty`, and templates vary. */
internal val FORGE_GITIGNORE = """
  # Compiler files
  cache/
  out/

  # Commonly ignored directories
  .logs/
  .ignore/

  # Ignores development broadcast logs
  !/broadcast
  /broadcast/*/31337/
  /broadcast/**/dry-run/

  # Docs
  docs/

  # Dotenv file
  .env
  .env.*
  !.env.example
""".trimIndent()

/** IDE files: settings, open files, Solidity Chains history and local chain dumps. */
private const val IDE_GITIGNORE = ".idea/"

/** Makes sure the project has a `.gitignore` covering forge's output and the IDE's `.idea` folder. */
internal fun ensureGitignore(dir: Path) {
  val file = dir.resolve(".gitignore")
  val text = if (Files.isRegularFile(file)) Files.readString(file) else FORGE_GITIGNORE + "\n"
  val ignoresIde = text.lines().any { it.trim().trimStart('/') in setOf(".idea", ".idea/") }
  val result = if (ignoresIde) text else text.trimEnd() + "\n\n# JetBrains IDE\n$IDE_GITIGNORE\n"
  if (!Files.isRegularFile(file) || result != text) Files.writeString(file, result)
}
