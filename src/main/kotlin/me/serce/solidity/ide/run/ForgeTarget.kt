package me.serce.solidity.ide.run

import javax.swing.JLabel
import java.awt.Component
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import me.serce.solidity.chain.ChainConfig
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.Networks
import me.serce.solidity.chain.rpc.JsonRpcClient
import org.jdom.Element
import javax.swing.JPanel

/**
 * Where `forge test` forks and `forge script` runs: nothing (a local in-memory chain), a network from the Networks
 * list, a chain of the Chains tool window, or a URL / `[rpc_endpoints]` alias typed in.
 */
enum class ForgeTargetKind(val title: String) {
  LOCAL("Local (in memory)"), NETWORK("Network"), CHAIN("Chain from the Chains tool window"), URL("RPC URL or endpoint alias");
}

/** A run configuration's target, saved with it. */
class ForgeTarget {
  var kind = ForgeTargetKind.LOCAL
  /** [ForgeTargetKind.NETWORK]: a [Networks] name. */
  var network = ""
  /** [ForgeTargetKind.CHAIN]: a [ChainConfig.id]. */
  var chain = ""
  /** [ForgeTargetKind.URL]: passed to forge as typed. */
  var url = ""

  fun describe(project: Project): String = when (kind) {
    ForgeTargetKind.LOCAL -> "local"
    ForgeTargetKind.NETWORK -> network
    ForgeTargetKind.CHAIN -> ChainService.getInstance(project).chain(chain)?.name ?: "missing chain"
    ForgeTargetKind.URL -> JsonRpcClient.redact(url)
  }

  fun write(element: Element, prefix: String) {
    element.put("${prefix}Target", kind.name)
    element.put("${prefix}Network", network)
    element.put("${prefix}Chain", chain)
  }

  /** [legacyUrl]: the URL field of configurations saved before targets existed, which is also [url]'s storage. */
  fun read(element: Element, prefix: String, legacyUrl: String) {
    url = legacyUrl
    kind = element.value("${prefix}Target").let { saved -> ForgeTargetKind.entries.firstOrNull { it.name == saved } }
      ?: if (legacyUrl.isNotBlank()) ForgeTargetKind.URL else ForgeTargetKind.LOCAL
    network = element.value("${prefix}Network")
    chain = element.value("${prefix}Chain")
  }

  /**
   * Where forge connects, or null for [ForgeTargetKind.LOCAL]. Reads the password safe and may ask the node for its
   * chain id: call it off the EDT, or it runs under a modal progress.
   */
  @Throws(ExecutionException::class)
  fun resolve(project: Project): ResolvedTarget? = when (kind) {
    ForgeTargetKind.LOCAL -> null
    ForgeTargetKind.URL -> url.trim().takeIf { it.isNotEmpty() }?.let { u ->
      // an alias is resolved by forge from foundry.toml; only a URL can be asked for its chain id
      val chainId = if (u.contains("://")) offEdt("Connecting to ${JsonRpcClient.redact(u)}") { runCatching { JsonRpcClient(u).chainId() }.getOrNull() } else null
      ResolvedTarget(u, JsonRpcClient.redact(u), chainId, chain = null, onCommandLine = true)
    } ?: throw ExecutionException("Enter an RPC URL or an [rpc_endpoints] alias, or pick another target")
    ForgeTargetKind.NETWORK -> {
      val url = offEdt("Reading network $network") { Networks.getInstance().find(network)?.url?.takeIf { it.isNotBlank() } }
        ?: throw ExecutionException("There is no network named \"$network\". Pick one in the run configuration.")
      val chainId = offEdt("Connecting to $network") {
        try {
          JsonRpcClient(url).chainId()
        } catch (e: Exception) {
          throw ExecutionException("Cannot connect to $network: ${e.message}")
        }
      }
      ResolvedTarget(url, network, chainId, chain = null, onCommandLine = false)
    }
    ForgeTargetKind.CHAIN -> {
      val service = ChainService.getInstance(project)
      val config = service.chain(chain) ?: throw ExecutionException("The chain of this run configuration was removed. Pick another target.")
      val rt = service.runtime(config.id)
      val rpc = rt.rpc?.takeIf { service.isRunning(config) }
        ?: throw ExecutionException("Chain \"${config.name}\" is not running. Start it in the Chains tool window first.")
      ResolvedTarget(rpc.url, config.name, rt.chainId, config, onCommandLine = false)
    }
  }

  fun copyFrom(other: ForgeTarget) {
    kind = other.kind; network = other.network; chain = other.chain; url = other.url
  }
}

/**
 * [url] may hold an API key: unless the user typed it ([onCommandLine]), it goes to forge in `FOUNDRY_ETH_RPC_URL`,
 * which both `forge test` (fork) and `forge script` (rpc) read, never on the command line where `ps` shows it.
 */
class ResolvedTarget(val url: String, val name: String, val chainId: Long?, val chain: ChainConfig?, val onCommandLine: Boolean) {
  fun applyTo(cmd: GeneralCommandLine, option: String) {
    if (onCommandLine) cmd.addParameters(option, url) else cmd.withEnvironment(RPC_ENV, url)
  }

  companion object {
    const val RPC_ENV = "FOUNDRY_ETH_RPC_URL"
  }
}

private fun <T> offEdt(title: String, body: () -> T): T =
  if (!ApplicationManager.getApplication().isDispatchThread) body()
  else ProgressManager.getInstance().runProcessWithProgressSynchronously<T, ExecutionException>(body, title, true, null)

/** A run configuration field, stored as a `<name value="..."/>` child. */
internal fun Element.value(name: String) = JDOMExternalizerUtil.readCustomField(this, name) ?: ""
internal fun Element.put(name: String, value: String) {
  removeChildren(name)
  JDOMExternalizerUtil.writeCustomField(this, name, value)
}

/** The target fields of a run configuration editor. */
class ForgeTargetEditor(private val project: Project, private val urlLabel: String) {
  val kind = ComboBox(ForgeTargetKind.entries.toTypedArray()).apply {
    renderer = SimpleListCellRenderer.create("") { it.title }
  }
  val network = ComboBox<String>()
  val chain = ComboBox<ChainConfig?>().apply {
    renderer = SimpleListCellRenderer.create("") { c -> c?.let { "${it.name} (${it.description})" } ?: "" }
  }
  val url = JBTextField()
  var onChange: () -> Unit = {}

  init {
    kind.addActionListener { updateVisibility(); onChange() }
    chain.addActionListener { onChange() }
  }

  fun addTo(form: FormBuilder): FormBuilder = form
    .addLabeledComponent("Target:", kind)
    .addLabeledComponent("Network:", network)
    .addLabeledComponent("Chain:", chain)
    .addLabeledComponent(urlLabel, url)

  private fun updateVisibility() {
    val k = kind.item ?: ForgeTargetKind.LOCAL
    setRowVisible(network, k == ForgeTargetKind.NETWORK)
    setRowVisible(chain, k == ForgeTargetKind.CHAIN)
    setRowVisible(url, k == ForgeTargetKind.URL)
  }

  /** FormBuilder puts a label before each field, whose `labelFor` is the field. */
  private fun setRowVisible(field: Component, visible: Boolean) {
    field.isVisible = visible
    field.parent?.components?.filterIsInstance<JLabel>()?.filter { it.labelFor === field }?.forEach { it.isVisible = visible }
  }

  val selectedChain: ChainConfig? get() = if (kind.item == ForgeTargetKind.CHAIN) chain.item else null

  fun reset(target: ForgeTarget) {
    network.removeAllItems()
    // names only: the URLs (which may be secrets) are read when the configuration runs
    (Networks.getInstance().names() + listOfNotNull(target.network.takeIf { it.isNotEmpty() })).distinct().forEach(network::addItem)
    network.item = target.network.takeIf { it.isNotEmpty() }
    chain.removeAllItems()
    val chains = ChainService.getInstance(project).chains
    chains.forEach(chain::addItem)
    chain.item = chains.firstOrNull { it.id == target.chain }
    url.text = target.url
    kind.item = target.kind
    updateVisibility()
  }

  fun apply(target: ForgeTarget) {
    target.kind = kind.item ?: ForgeTargetKind.LOCAL
    target.network = network.item.orEmpty()
    target.chain = chain.item?.id.orEmpty()
    target.url = url.text.trim()
  }

}
