package me.serce.solidity.ide.coverage

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.ide.foundry.ForgeAction

/** Runs `forge coverage --report lcov` in the Foundry root and shows the result in the editors. */
class RunForgeCoverageAction : ForgeAction("Forge coverage") {
  override fun taskTitle() = "Running forge coverage"

  override fun runInBackground(project: Project, foundry: FoundryProject) {
    val report = foundry.root.resolve("lcov.info").toFile()
    val started = System.currentTimeMillis()
    val output = forge(project, foundry, listOf("coverage", "--report", "lcov")) ?: return
    // forge writes no report when a test fails: don't show a stale one
    if (!report.isFile || report.lastModified() < started - 2_000) {
      notify(project, "forge coverage wrote no lcov.info (exit code ${output.exitCode})\n${tail(output)}", NotificationType.ERROR)
      return
    }
    val lcov = Lcov.parse(report.readText())
    LocalFileSystem.getInstance().refreshIoFiles(listOf(report), true, false, null)
    ApplicationManager.getApplication().invokeLater({
      ForgeCoverageService.getInstance(project).setReport(foundry.root.toString(), lcov, started)
    }, project.disposed)
    val summary = CoverageSummary.of(lcov).toString()
    val (content, type) = if (output.exitCode != 0) {
      "$summary<br>forge coverage exited with code ${output.exitCode} (a test failed?); showing the report it wrote" to NotificationType.WARNING
    } else {
      summary to NotificationType.INFORMATION
    }
    notification(content, type).addAction(object : NotificationAction("Hide") {
      override fun actionPerformed(e: AnActionEvent, notification: Notification) {
        ForgeCoverageService.getInstance(project).clear()
        notification.expire()
      }
    }).notify(project)
  }
}

class HideForgeCoverageAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && ForgeCoverageService.getInstance(project).isActive
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.let { ForgeCoverageService.getInstance(it).clear() }
  }
}
