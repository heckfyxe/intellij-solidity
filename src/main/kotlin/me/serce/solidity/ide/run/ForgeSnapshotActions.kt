package me.serce.solidity.ide.run

import me.serce.solidity.chain.foundry.ToolOutput
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.ide.foundry.ForgeAction

/** A test whose gas differs from `.gas-snapshot`, from `forge snapshot --check`. */
data class GasSnapshotDiff(val test: String, val actual: String, val expected: String)

object ForgeSnapshotOutput {
  // Diff in "ATest::test_A()": consumed "(gas: 22347)" gas, expected "(gas: 22234)" gas
  private val DIFF = Regex("""Diff in "([^"]+)": consumed "\(([^"]*)\)" gas, expected "\(([^"]*)\)" gas""")

  fun diffs(output: String): List<GasSnapshotDiff> =
    DIFF.findAll(output).map { m -> GasSnapshotDiff(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }.toList()
}

/** `forge snapshot` (writes `.gas-snapshot`) or `forge snapshot --check` (compares the tests' gas with it). */
abstract class ForgeSnapshotAction(private val check: Boolean) : ForgeAction("Forge snapshot") {
  override fun taskTitle() = if (check) "Checking forge gas snapshot" else "Running forge snapshot"

  override fun runInBackground(project: Project, foundry: FoundryProject) {
    val output = forge(project, foundry, listOf("snapshot") + if (check) listOf("--check") else emptyList()) ?: return
    val snapshot = foundry.root.resolve(GasSnapshot.FILE_NAME).toFile()
    // the hints are redrawn by GasSnapshotChangeListener
    LocalFileSystem.getInstance().refreshIoFiles(listOf(snapshot), true, false, null)
    if (check) reportCheck(project, output) else reportRun(project, output)
  }

  private fun reportRun(project: Project, output: ProcessOutput) {
    val exitCode = output.exitCode
    if (exitCode == 0) {
      notify(project, "${GasSnapshot.FILE_NAME} updated", NotificationType.INFORMATION)
    } else {
      notify(project, "forge snapshot failed (exit code $exitCode)\n${ToolOutput.tail(output)}", NotificationType.ERROR)
    }
  }

  private fun reportCheck(project: Project, output: ProcessOutput) {
    val exitCode = output.exitCode
    val diffs = ForgeSnapshotOutput.diffs(output.stdout + output.stderr)
    when {
      exitCode == 0 && diffs.isEmpty() ->
        notify(project, "Gas matches ${GasSnapshot.FILE_NAME}", NotificationType.INFORMATION)
      diffs.isNotEmpty() -> {
        val shown = diffs.take(MAX_DIFFS).joinToString("<br>") {
          StringUtil.escapeXmlEntities("${it.test}: ${it.actual}, expected ${it.expected}")
        }
        val more = if (diffs.size > MAX_DIFFS) "<br>and ${diffs.size - MAX_DIFFS} more" else ""
        notify(project, "${diffs.size} test(s) differ from ${GasSnapshot.FILE_NAME}:<br>$shown$more", NotificationType.WARNING)
      }
      else -> notify(
        project, "forge snapshot --check failed (exit code $exitCode)\n${ToolOutput.tail(output)}", NotificationType.ERROR
      )
    }
  }

  private companion object {
    const val MAX_DIFFS = 10
  }
}

class RunForgeSnapshotAction : ForgeSnapshotAction(check = false)

class CheckForgeSnapshotAction : ForgeSnapshotAction(check = true)
