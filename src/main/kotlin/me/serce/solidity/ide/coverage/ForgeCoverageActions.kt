package me.serce.solidity.ide.coverage

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import me.serce.solidity.chain.foundry.FoundryException
import me.serce.solidity.chain.foundry.FoundryProject

/** Runs `forge coverage --report lcov` in the Foundry root and shows the result in the editors. */
class RunForgeCoverageAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val foundry = FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
    FileDocumentManager.getInstance().saveAllDocuments()
    object : Task.Backgroundable(project, "Running forge coverage", true) {
      override fun run(indicator: ProgressIndicator) {
        val report = foundry.root.resolve("lcov.info").toFile()
        val started = System.currentTimeMillis()
        val output = try {
          foundry.exec(listOf(FoundryProject.forgePath(project), "coverage", "--report", "lcov"), timeoutMs = 30 * 60_000)
        } catch (ex: FoundryException) {
          notify(project, ex.message ?: "forge coverage failed", NotificationType.ERROR)
          return
        }
        // forge writes no report when a test fails: don't show a stale one
        if (!report.isFile || report.lastModified() < started - 2_000) {
          val text = (output.stdout + output.stderr).trim().lines().takeLast(15).joinToString("\n")
          notify(project, "forge coverage wrote no lcov.info (exit code ${output.exitCode})\n$text", NotificationType.ERROR)
          return
        }
        val lcov = Lcov.parse(report.readText())
        LocalFileSystem.getInstance().refreshIoFiles(listOf(report), true, false, null)
        ApplicationManager.getApplication().invokeLater({
          ForgeCoverageService.getInstance(project).setReport(foundry.root.toString(), lcov)
        }, project.disposed)
        if (output.exitCode != 0) {
          notify(project, "forge coverage exited with code ${output.exitCode}; showing the report it wrote", NotificationType.WARNING)
        }
      }
    }.queue()
  }

  private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Solidity Foundry")
      .createNotification("Forge coverage", content, type).notify(project)
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
