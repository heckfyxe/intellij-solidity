package me.serce.solidity.chain.foundry

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProgressManager
import java.io.File
import java.nio.file.Path

/** Runs Foundry's tools (forge, cast, anvil) and solc. Their paths come from [FoundryProject.forgePath] and [FoundryProject.toolPath]. */
object FoundryTools {
  /**
   * [command] with UTF-8 output and `NO_COLOR` set, run in [workDir]. [environment] is added before `NO_COLOR`; pass
   * the whole environment with `ParentEnvironmentType.NONE` to leave variables out.
   */
  fun commandLine(command: List<String>, workDir: Path? = null, environment: Map<String, String> = emptyMap()): GeneralCommandLine {
    val cmd = GeneralCommandLine(command).withCharset(Charsets.UTF_8).withEnvironment(environment).withEnvironment("NO_COLOR", "1")
    workDir?.let { cmd.withWorkDirectory(it.toFile()) }
    return cmd
  }

  /**
   * Runs [cmd] with [stdin] and waits for it at most [timeoutMs]. Cancelling the current progress indicator kills it.
   * Throws [FoundryException] when the tool is missing, times out or is cancelled; a failing exit code is the caller's
   * to explain, with [ToolOutput.errorText].
   */
  fun run(cmd: GeneralCommandLine, timeoutMs: Int, stdin: ByteArray? = null): ProcessOutput {
    val exe = executable(cmd.exePath)
      ?: throw FoundryException("${File(cmd.exePath).name} not found at ${cmd.exePath}. Install Foundry with foundryup or set its path in Settings | Solidity")
    cmd.exePath = exe.path
    val handler = CapturingProcessHandler(cmd)
    stdin?.let { bytes -> handler.processInput.use { it.write(bytes) } }
    val indicator = ProgressManager.getInstance().progressIndicator
    val output = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMs) else handler.runProcess(timeoutMs)
    val what = "${exe.nameWithoutExtension} ${cmd.parametersList.list.firstOrNull().orEmpty()}".trim()
    if (output.isCancelled) throw FoundryException("Cancelled", cancelled = true)
    if (output.isTimeout) throw FoundryException("$what did not finish within ${timeoutMs / 1000} seconds")
    return output
  }

  /** [path] if it can be run; a bare name (`forge`) is looked up on the PATH. */
  private fun executable(path: String): File? {
    val file = File(path)
    if (file.isAbsolute || path.contains('/') || path.contains('\\')) return file.takeIf { it.canExecute() }
    return PathEnvironmentVariableUtil.findInPath(path)
  }
}

/** Explaining a tool's failure from what it printed. */
object ToolOutput {
  /** The tool's own `Error: …` line and the context after it, on one line, without the noise around it; else its last lines. */
  fun errorText(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val start = lines.indexOfFirst { it.startsWith("Error:") }
    val relevant = if (start >= 0) lines.drop(start) else lines.takeLast(3)
    return relevant.filter { it != "Context:" }.joinToString(" ") { it.removePrefix("Error:").removePrefix("- ").trim() }.take(500)
  }

  /** [errorText] of stderr and stdout, or the exit code when the tool printed nothing. */
  fun errorText(output: ProcessOutput): String =
    errorText(output.stderr + "\n" + output.stdout).ifEmpty { "exit code ${output.exitCode}" }

  /** The last lines of the output, for a failure the tool explains itself (a failing test, a compile error). */
  fun tail(output: ProcessOutput, lines: Int = 15): String =
    (output.stdout + output.stderr).trim().lines().takeLast(lines).joinToString("\n")
}
