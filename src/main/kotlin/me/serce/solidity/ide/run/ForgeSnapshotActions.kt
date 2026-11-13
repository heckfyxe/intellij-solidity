package me.serce.solidity.ide.run

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import me.serce.solidity.chain.foundry.FoundryException
import me.serce.solidity.chain.foundry.FoundryProject

/** A test whose gas differs from `.gas-snapshot`, from `forge snapshot --check`. */
data class GasSnapshotDiff(val test: String, val actual: String, val expected: String)

object ForgeSnapshotOutput {
  // Diff in "ATest::test_A()": consumed "(gas: 22347)" gas, expected "(gas: 22234)" gas
  private val DIFF = Regex("""Diff in "([^"]+)": consumed "\(([^"]*)\)" gas, expected "\(([^"]*)\)" gas""")

  fun diffs(output: String): List<GasSnapshotDiff> =
    DIFF.findAll(output).map { m -> GasSnapshotDiff(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }.toList()

  /** The last lines of forge's output, for a failure forge explains itself (a failing test, a compile error). */
  fun tail(output: String, lines: Int = 15): String = output.trim().lines().takeLast(lines).joinToString("\n")
}

/** `forge snapshot` (writes `.gas-snapshot`) or `forge snapshot --check` (compares the tests' gas with it). */
abstract class ForgeSnapshotAction(private val check: Boolean) : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val foundry = FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
    FileDocumentManager.getInstance().saveAllDocuments()
    val title = if (check) "Checking forge gas snapshot" else "Running forge snapshot"
    object : Task.Backgroundable(project, title, true) {
      override fun run(indicator: ProgressIndicator) {
        val command = listOf(FoundryProject.forgePath(project), "snapshot") + if (check) listOf("--check") else emptyList()
        val output = try {
          foundry.exec(command, timeoutMs = 30 * 60_000)
        } catch (ex: FoundryException) {
          notify(project, ex.message ?: "forge snapshot failed", NotificationType.ERROR)
          return
        }
        val text = output.stdout + output.stderr
        val snapshot = foundry.root.resolve(GasSnapshot.FILE_NAME).toFile()
        // the hints are redrawn by GasSnapshotChangeListener
        LocalFileSystem.getInstance().refreshIoFiles(listOf(snapshot), true, false, null)
        if (check) reportCheck(project, output.exitCode, text) else reportRun(project, output.exitCode, text)
      }
    }.queue()
  }

  private fun reportRun(project: Project, exitCode: Int, text: String) {
    if (exitCode == 0) {
      notify(project, "${GasSnapshot.FILE_NAME} updated", NotificationType.INFORMATION)
    } else {
      notify(project, "forge snapshot failed (exit code $exitCode)\n${ForgeSnapshotOutput.tail(text)}", NotificationType.ERROR)
    }
  }

  private fun reportCheck(project: Project, exitCode: Int, text: String) {
    val diffs = ForgeSnapshotOutput.diffs(text)
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
        project, "forge snapshot --check failed (exit code $exitCode)\n${ForgeSnapshotOutput.tail(text)}", NotificationType.ERROR
      )
    }
  }

  private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Solidity Foundry")
      .createNotification("Forge snapshot", content, type).notify(project)
  }

  private companion object {
    const val MAX_DIFFS = 10
  }
}

class RunForgeSnapshotAction : ForgeSnapshotAction(check = false)

class CheckForgeSnapshotAction : ForgeSnapshotAction(check = true)
