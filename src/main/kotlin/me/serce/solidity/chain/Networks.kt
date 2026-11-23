package me.serce.solidity.chain

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * A network that local chains can fork and network chains connect to, e.g. Ethereum Mainnet at a public RPC.
 * [secret]: the URL is kept in the password safe (it may hold an API key) and [url] is empty in the settings file.
 */
class Network(var name: String = "", var url: String = "", var secret: Boolean = false) {
  override fun toString() = name

  companion object {
    /** URLs compare equal regardless of surrounding spaces and a trailing slash. */
    fun sameUrl(a: String, b: String) = a.trim().trimEnd('/') == b.trim().trimEnd('/')
  }
}

/** The user's list of networks, shared by all projects. Starts with public RPCs of popular networks. */
@Service(Service.Level.APP)
@State(name = "SolidityNetworks", storages = [Storage("solidity-networks.xml")])
class Networks : PersistentStateComponent<Networks.State> {
  class State {
    var networks: MutableList<Network> = DEFAULTS.map { Network(it.name, it.url) }.toMutableList()
  }

  private var state = State()

  override fun getState() = state
  override fun loadState(loaded: State) {
    state = loaded
    resolved = null
  }

  /** [list] with secret URLs read once: the tree renderer names forks on every repaint. */
  @Volatile private var resolved: List<Network>? = null

  /** With the URLs of secret networks read from the password safe, which can be slow: avoid calling it repeatedly on the EDT. */
  var list: List<Network>
    get() = (resolved ?: state.networks.map { Network(it.name, if (it.secret) Secrets.get(Secrets.networkUrlKey(it.name)).orEmpty() else it.url, it.secret) }
      .also { resolved = it }).map { Network(it.name, it.url, it.secret) }
    set(value) {
      resolved = null
      val old = state.networks.filter { it.secret }.map { it.name }.toSet()
      val kept = value.map { Network(it.name.trim(), it.url.trim()) }.filter { it.name.isNotEmpty() && it.url.isNotEmpty() }.distinctBy { it.name }
      state.networks = kept.map { n ->
        // Public RPCs we ship are not secrets; any other URL may carry an API key (Alchemy, Infura, QuickNode, …).
        if (DEFAULTS.any { Network.sameUrl(it.url, n.url) }) Network(n.name, n.url)
        else Network(n.name, "", secret = true).also { Secrets.set(Secrets.networkUrlKey(n.name), n.url) }
      }.toMutableList()
      (old - kept.map { it.name }.toSet()).forEach { Secrets.set(Secrets.networkUrlKey(it), null) }
    }

  fun find(name: String): Network? = list.firstOrNull { it.name == name }

  fun nameOf(url: String): String? = list.firstOrNull { Network.sameUrl(it.url, url) }?.name

  companion object {
    val DEFAULTS = listOf(
      Network("Ethereum Mainnet", "https://ethereum-rpc.publicnode.com"),
      Network("Sepolia", "https://ethereum-sepolia-rpc.publicnode.com"),
      Network("Hoodi", "https://ethereum-hoodi-rpc.publicnode.com"),
      Network("Base", "https://base-rpc.publicnode.com"),
      Network("Arbitrum One", "https://arbitrum-one-rpc.publicnode.com"),
      Network("Optimism", "https://optimism-rpc.publicnode.com"),
      Network("Polygon", "https://polygon-bor-rpc.publicnode.com"),
      Network("BNB Smart Chain", "https://bsc-rpc.publicnode.com"),
      Network("Avalanche C-Chain", "https://avalanche-c-chain-rpc.publicnode.com"),
    )

    fun getInstance(): Networks = service()
  }
}
