package me.serce.solidity.chain.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import me.serce.solidity.chain.ChainExecutor
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.Deployment
import me.serce.solidity.chain.abi.AbiEntry
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.lang.psi.ContractType
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.Visibility
import java.awt.event.MouseEvent

/**
 * Gutter icons: "Deploy" on deployable contracts, and "Call" on public functions of contracts that are
 * deployed on some chain.
 */
class ChainLineMarkerProvider : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    val file = elements.firstOrNull()?.containingFile ?: return
    val vFile = file.virtualFile ?: return
    val foundry = FoundryProject.find(file.project, vFile) ?: return
    val relative = foundry.relativePath(vFile) ?: return
    // Runs under a read action, so no forge here: read the config in the background, then highlight again.
    val cached = foundry.cachedConfig()
      ?: return foundry.loadConfigInBackground { invokeLater { if (file.isValid) DaemonCodeAnalyzer.getInstance(file.project).restart(file) } }
    val config = cached.getOrNull() ?: return // no forge or a broken foundry.toml: nothing to deploy with
    // same rule as the Deploy action: contracts under foundry.toml's `src`, not tests or scripts
    if (!foundry.isSource(relative, config)) return
    val deployed = ChainService.getInstance(file.project).allDeployments().filter { it.sourcePath == relative }.mapTo(HashSet()) { it.name }

    for (el in elements) {
      when (el) {
        is SolContractDefinition -> {
          val id = el.identifier ?: continue
          if (el.contractType != ContractType.COMMON || el.isAbstract) continue
          val name = el.name ?: continue
          result += LineMarkerInfo(id, id.textRange, AllIcons.Nodes.Deploy, { "Deploy $name to a chain" },
            GutterIconNavigationHandler<PsiElement> { e, elt -> deploy(e, elt, relative, name) },
            GutterIconRenderer.Alignment.LEFT, { "Deploy $name" })
        }
        is SolFunctionDefinition -> {
          val id = el.identifier ?: continue
          val name = el.name ?: continue
          if (el.isConstructor || el.specialFunction != null || el.visibility == Visibility.PRIVATE || el.visibility == Visibility.INTERNAL) continue
          val contractName = el.contract?.name ?: continue
          if (contractName !in deployed) continue
          val arity = el.parameters.size
          result += LineMarkerInfo(id, id.textRange, AllIcons.Actions.Execute, { "Call $name on a deployed $contractName" },
            GutterIconNavigationHandler<PsiElement> { e, elt -> call(e, elt, contractName, relative, name, arity) },
            GutterIconRenderer.Alignment.LEFT, { "Call $name" })
        }
      }
    }
  }

  private fun deploy(e: MouseEvent, element: PsiElement, sourcePath: String, contract: String) {
    val project = element.project
    val service = ChainService.getInstance(project)
    val running = service.runningChains()
    if (running.isEmpty()) return ChainUi.noRunningChain(project, "Start a chain in the Solidity Chains tool window first.")
    val foundry = FoundryProject.find(project, element.containingFile.virtualFile) ?: return
    val where = RelativePoint(e)
    ChainUi.buildThen(project, foundry) { artifacts ->
      val artifact = artifacts.firstOrNull { it.name == contract && it.sourcePath == sourcePath }
      if (artifact == null || !artifact.isDeployable) {
        Messages.showWarningDialog(project, "No bytecode for $contract after forge build" +
          (if (artifact?.hasLinkReferences == true) " (it needs linked libraries, which are not supported yet)" else ""), "Cannot Deploy")
        return@buildThen
      }
      ChainUi.choose("Deploy $contract to", running, { it.name }, where, project) { chain ->
        ChainUi.showFormPopup(project, ChainUi.deployForm(project, chain, artifact), where, closeOnSuccess = true)
      }
    }
  }

  private fun call(e: MouseEvent, element: PsiElement, contract: String, sourcePath: String, function: String, arity: Int) {
    val project = element.project
    val service = ChainService.getInstance(project)
    val where = RelativePoint(e)
    val targets = service.allDeployments().filter { it.name == contract && it.sourcePath == sourcePath }
      .filter { d -> service.chain(d.chainId)?.let(service::isRunning) == true }
    if (targets.isEmpty()) return ChainUi.noRunningChain(project, "$contract is deployed only on chains that are stopped. Start one in the Solidity Chains tool window.")
    ChainUi.choose("Call $function on", targets, { d -> "${service.chain(d.chainId)?.name}  ${ChainExecutor.short(d.address)}" }, where, project) { d: Deployment ->
      val fn: AbiEntry.Function = d.abi().functions.firstOrNull { it.name == function && it.inputs.size == arity }
        ?: d.abi().functions.firstOrNull { it.name == function }
        ?: run {
          Messages.showWarningDialog(project, "The deployed $contract has no function $function. Redeploy it after your changes.", "Function Not Found")
          return@choose
        }
      val chain = service.chain(d.chainId) ?: return@choose
      ChainUi.showFormPopup(project, ChainUi.callForm(project, chain, d, fn), where)
    }
  }
}
