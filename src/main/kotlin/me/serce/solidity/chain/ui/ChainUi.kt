package me.serce.solidity.chain.ui

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.awt.RelativePoint
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainExecutor
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.Deployment
import me.serce.solidity.chain.TxStatus
import me.serce.solidity.chain.chainNotification
import me.serce.solidity.chain.abi.AbiEntry
import me.serce.solidity.chain.abi.Mutability
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.foundry.BuildResult
import me.serce.solidity.chain.foundry.FoundryException
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import java.io.File
import java.nio.file.Path

object ChainUi {

  /** Runs `forge build` in the background, reports to the Build tool window, then calls [onSuccess] on the EDT. */
  fun buildThen(project: Project, foundry: FoundryProject, onSuccess: (List<Artifact>) -> Unit) {
    object : Task.Backgroundable(project, "Building with forge", true) {
      private var result: BuildResult? = null
      private var artifacts: List<Artifact> = emptyList()
      private var error: String? = null

      override fun run(indicator: ProgressIndicator) {
        try {
          result = foundry.build()
          if (result!!.success) artifacts = foundry.artifacts()
        } catch (e: FoundryException) {
          error = e.message
        }
        reportBuild(project, foundry, result, error) // BuildViewManager is thread-safe; keeps file reads off the EDT
      }

      override fun onSuccess() {
        if (result?.success == true) onSuccess(artifacts)
      }
    }.queue()
  }

  private fun reportBuild(project: Project, foundry: FoundryProject, result: BuildResult?, error: String?) {
    val id = Any()
    val view = project.service<BuildViewManager>()
    val descriptor = DefaultBuildDescriptor(id, "forge build", foundry.root.toString(), System.currentTimeMillis()).apply {
      isActivateToolWindowWhenAdded = false
      isActivateToolWindowWhenFailed = true
    }
    view.onEvent(id, StartBuildEventImpl(descriptor, "forge build"))
    val positions = HashMap<File, LinePositions>()
    for (m in result?.messages.orEmpty()) {
      val kind = when (m.severity) {
        "error" -> MessageEvent.Kind.ERROR
        "warning" -> MessageEvent.Kind.WARNING
        else -> MessageEvent.Kind.INFO
      }
      val file = m.file?.let { foundry.root.resolve(it).toFile() }
      if (file != null && file.exists() && m.start >= 0) {
        val (line, col) = positions.getOrPut(file) { LinePositions(file.readText()) }.lineCol(m.start)
        view.onEvent(id, FileMessageEventImpl(id, kind, m.type, m.message, m.formatted, FilePosition(file, line, col)))
      } else {
        view.onEvent(id, MessageEventImpl(id, kind, m.type, m.message.lines().first(), m.formatted))
      }
    }
    if (error != null) view.onEvent(id, MessageEventImpl(id, MessageEvent.Kind.ERROR, "forge", error, error))
    val ok = error == null && result?.success == true
    view.onEvent(id, FinishBuildEventImpl(id, null, System.currentTimeMillis(), if (ok) "successful" else "failed",
      if (ok) SuccessResultImpl() else FailureResultImpl()))
  }

  fun deployForm(project: Project, chain: ChainConfig, artifact: Artifact): FunctionForm {
    val ctor = artifact.abi.constructor
    val executor = ChainExecutor(ChainService.getInstance(project))
    return FunctionForm(
      project, chain, "Deploy ${artifact.name} to ${chain.name}", ctor?.inputs.orEmpty(),
      payable = ctor?.mutability == Mutability.PAYABLE, actionName = "Deploy",
      action = { args, from, value -> executor.deploy(chain, artifact, args, from, value) },
    ).apply { whenDone { outcome -> outcome.deployment?.let { notifyDeployed(project, chain, it) } } }
  }

  private fun notifyDeployed(project: Project, chain: ChainConfig, d: Deployment) {
    chainNotification("${d.name} deployed to ${chain.name}", d.address, NotificationType.INFORMATION)
      .addAction(NotificationAction.createSimpleExpiring("Show in Chains") { ChainsToolWindowFactory.show(project) })
      .notify(project)
  }

  /** Points the user at the Chains tool window when nothing is running to deploy to or call. */
  fun noRunningChain(project: Project, message: String) {
    Messages.showInfoMessage(project, message, "No Running Chain")
    ChainsToolWindowFactory.show(project)
  }

  fun callForm(project: Project, chain: ChainConfig, deployment: Deployment, fn: AbiEntry.Function): FunctionForm {
    val executor = ChainExecutor(ChainService.getInstance(project))
    val title = "${deployment.name}.${fn.name}(${fn.inputs.joinToString(", ") { it.displayType }})" +
      (if (fn.outputs.isNotEmpty()) " → ${fn.outputs.joinToString(", ") { it.displayType }}" else "")
    return FunctionForm(
      project, chain, title, fn.inputs,
      payable = fn.mutability == Mutability.PAYABLE,
      actionName = if (fn.mutability.isReadOnly) "Call" else "Transact",
      action = { args, from, value -> executor.invoke(chain, deployment, fn, args, from, value) },
    )
  }

  /** [closeOnSuccess]: close once the action succeeds (deploys); calls stay open to show their result. */
  fun showFormPopup(project: Project, form: FunctionForm, where: RelativePoint?, closeOnSuccess: Boolean = false) {
    val popup: JBPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(form.component, form.preferredFocus)
      .setProject(project)
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(true)
      .setCancelOnClickOutside(true)
      .createPopup()
    // grow with the result instead of reserving empty space up front
    form.onResize = { if (!popup.isDisposed) popup.pack(true, true) }
    if (closeOnSuccess) form.whenDone { if (it.record.status == TxStatus.SUCCESS && !popup.isDisposed) popup.cancel() }
    if (where != null) popup.show(where) else popup.showCenteredInCurrentWindow(project)
  }

  /** Lets the user pick one of [items] (skipped when there is just one), then calls [onChosen]. */
  fun <T> choose(title: String, items: List<T>, text: (T) -> String, where: RelativePoint?, project: Project, onChosen: (T) -> Unit) {
    if (items.size == 1) {
      onChosen(items[0])
      return
    }
    val step = object : BaseListPopupStep<T>(title, items) {
      override fun getTextFor(value: T) = text(value)
      override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
        doFinalStep { onChosen(selectedValue) }
        return FINAL_CHOICE
      }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    if (where != null) popup.show(where) else popup.showCenteredInCurrentWindow(project)
  }

  fun navigate(project: Project, root: Path?, sourcePath: String, contract: String, function: String? = null) {
    if (root == null || sourcePath.isEmpty()) return
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root.resolve(sourcePath).toFile()) ?: return
    val offset = runReadAction {
      val psi = PsiManager.getInstance(project).findFile(vf) ?: return@runReadAction 0
      val c = PsiTreeUtil.findChildrenOfType(psi, SolContractDefinition::class.java).firstOrNull { it.name == contract }
      val f = function?.let { fn -> c?.let { PsiTreeUtil.findChildrenOfType(it, SolFunctionDefinition::class.java).firstOrNull { it.name == fn } } }
      (f?.identifier ?: c?.identifier)?.textOffset ?: 0
    }
    invokeLater { OpenFileDescriptor(project, vf, offset).navigate(true) }
  }
}

object Offsets {
  /** solc reports UTF-8 byte offsets; IntelliJ wants char offsets. */
  fun byteToChar(text: String, byteOffset: Int): Int {
    var bytes = 0
    var i = 0
    while (i < text.length && bytes < byteOffset) {
      val cp = text.codePointAt(i)
      bytes += when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
      }
      i += Character.charCount(cp)
    }
    return i
  }

}

/** Zero-based line and column of solc byte offsets in one file's [text]. */
class LinePositions(private val text: String) {
  private val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

  fun lineCol(byteOffset: Int): Pair<Int, Int> {
    val offset = Offsets.byteToChar(text, byteOffset)
    val line = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
    return line to (offset - lineStarts[line])
  }
}
