package me.serce.solidity.chain.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class ChainsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val factory = ContentFactory.getInstance()
    val chains = ChainsPanel(project)
    val history = HistoryPanel(project)
    toolWindow.contentManager.addContent(factory.createContent(chains, "Chains", false).also { Disposer.register(it, chains) })
    toolWindow.contentManager.addContent(factory.createContent(history, "Transactions", false).also { Disposer.register(it, history) })
  }

  companion object {
    const val ID = "Solidity Chains"

    fun show(project: Project, tab: String = "Chains") {
      val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
      tw.activate {
        tw.contentManager.findContent(tab)?.let { tw.contentManager.setSelectedContent(it) }
      }
    }
  }
}
