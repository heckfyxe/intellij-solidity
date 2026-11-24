package me.serce.solidity.ide.foundry

import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.Notification
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
import me.serce.solidity.chain.foundry.FoundryException
import me.serce.solidity.chain.foundry.FoundryProject

/**
 * An action that saves all documents and runs a forge command in the background, in the Foundry root of the
 * selected file (or the project's), reporting in a "Solidity Foundry" notification titled [notificationTitle].
 */
abstract class ForgeAction(private val notificationTitle: String) : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val foundry = FoundryProject.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
    FileDocumentManager.getInstance().saveAllDocuments()
    object : Task.Backgroundable(project, taskTitle(), true) {
      override fun run(indicator: ProgressIndicator) = runInBackground(project, foundry)
    }.queue()
  }

  /** The background task's title. */
  protected abstract fun taskTitle(): String

  protected abstract fun runInBackground(project: Project, foundry: FoundryProject)

  /** Runs `forge <args>`; null after reporting a failure to start it or a timeout. */
  protected fun forge(project: Project, foundry: FoundryProject, args: List<String>): ProcessOutput? = try {
    foundry.exec(listOf(FoundryProject.forgePath(project)) + args, timeoutMs = 30 * 60_000)
  } catch (ex: FoundryException) {
    notify(project, ex.message ?: "forge ${args.firstOrNull().orEmpty()} failed", NotificationType.ERROR)
    null
  }

  protected fun notification(content: String, type: NotificationType): Notification =
    NotificationGroupManager.getInstance().getNotificationGroup("Solidity Foundry")
      .createNotification(notificationTitle, content, type)

  protected fun notify(project: Project, content: String, type: NotificationType) {
    notification(content, type).notify(project)
  }

  companion object {
    /** The last lines of forge's output, for a failure forge explains itself (a failing test, a compile error). */
    fun tail(output: ProcessOutput, lines: Int = 15): String =
      (output.stdout + output.stderr).trim().lines().takeLast(lines).joinToString("\n")
  }
}
