package me.serce.solidity.ide.formatting

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService.Feature
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiFile
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.ConfigurationMode
import me.serce.solidity.settings.FormatterType
import me.serce.solidity.settings.SoliditySettings
import org.jetbrains.annotations.VisibleForTesting
import java.nio.charset.StandardCharsets
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ExecutionException

class SolidityExternalFormatter : AsyncDocumentFormattingService() {

  override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
    val formattingContext: FormattingContext = request.context
    val project = formattingContext.project
    val settings = SoliditySettings.getInstance(project)

    val psiFile: PsiFile = request.context.containingFile
    val foundryExePath = forgeExecutable(settings)

    return try {
      // forge fmt reads the [fmt] section of the foundry.toml it finds from its working directory
      val workDir = foundryRoot(psiFile) ?: project.guessProjectDir()?.canonicalPath
      val cmd = generateCmd(foundryExePath, workDir, settings)
      val handler = OSProcessHandler(cmd)
      handler.processInput.use { outputStream ->
        outputStream.write(psiFile.text.toByteArray())
        outputStream.flush()
      }
      object : FormattingTask {
        override fun run() {
          val adapter = object : CapturingProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
              val exitCode = event.exitCode
              if (exitCode == 0) {
                request.onTextReady(output.stdout)
              } else {
                val onlyErrors = removeWarning(output.stderr).ifBlank { "Formatting failed with exit code $exitCode" }
                request.onError("Solidity", onlyErrors)
              }
            }
          }
          handler.addProcessListener(adapter)
          handler.startNotify()
        }

        override fun cancel(): Boolean {
          handler.destroyProcess()
          return true
        }

        override fun isRunUnderProgress(): Boolean = true
      }
    } catch (e: ExecutionException) {
      request.onError("Solidity", e.message ?: "Unknown error")
      null
    }
  }

  fun generateCmd(foundryExePath: String, projectPath: String?, settings: SoliditySettings): GeneralCommandLine {
    val cmd = GeneralCommandLine()
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
      .withExePath(foundryExePath).withParameters(buildList {
        add("fmt")
        add("-")
        add("--raw")

        if (settings.formatterFoundryConfigPath.isNotBlank() && settings.formatterConfigurationMode == ConfigurationMode.MANUAL) {
          add("--root")
          add(settings.formatterFoundryConfigPath)
        }
      }).withCharset(StandardCharsets.UTF_8)
    if (projectPath != null && Paths.get(projectPath).toFile().exists()) {
      cmd.withWorkDirectory(projectPath)
    }
    return cmd
  }

  private fun isWarning(line: String): Boolean {
    val l = line.trim().lowercase()
    return l.startsWith("warning:")
  }

  @VisibleForTesting
  fun removeWarning(stderr: String): String {
    val lines = stderr.lineSequence().toList()
    val errors = lines.filter { !isWarning(it) }
    return when {
      errors.isNotEmpty() -> errors.joinToString("\n")
      else -> stderr
    }
  }

  override fun getNotificationGroupId(): String {
    return "Solidity"
  }

  override fun getName(): String {
    return "Solidity"
  }

  override fun getFeatures(): Set<Feature?> {
    return EnumSet.noneOf(Feature::class.java)
  }

  /**
   * Like other external formatters, forge fmt takes over Reformat Code only when it can run: with automatic
   * configuration that means forge is installed; a manually configured path is always used, so mistakes are reported.
   */
  override fun canFormat(file: PsiFile): Boolean {
    if (file !is SolidityFile) {
      return false
    }
    val project: Project = file.project
    val settings = SoliditySettings.getInstance(project)
    return when (settings.formatterType) {
      FormatterType.DISABLED -> false
      else -> settings.formatterConfigurationMode == ConfigurationMode.MANUAL || File(forgeExecutable(settings)).canExecute()
    }
  }

  private fun forgeExecutable(settings: SoliditySettings): String =
    resolveForgeExecutable(settings.formatterFoundryExecutablePath, settings.formatterConfigurationMode, SystemInfo.isWindows)

  /** The closest folder above the file that has a foundry.toml. */
  private fun foundryRoot(file: PsiFile): String? =
    generateSequence(file.virtualFile?.parent) { it.parent }.firstOrNull { it.findChild("foundry.toml") != null }?.path
}
