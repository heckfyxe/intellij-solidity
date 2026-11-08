package me.serce.solidity.chain

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** A network that local chains can fork, e.g. Ethereum Mainnet at a public RPC. */
class Network(var name: String = "", var url: String = "") {
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
  }

  var list: List<Network>
    get() = state.networks.map { Network(it.name, it.url) }
    set(value) {
      state.networks = value.map { Network(it.name.trim(), it.url.trim()) }.filter { it.name.isNotEmpty() && it.url.isNotEmpty() }.toMutableList()
    }

  fun nameOf(url: String): String? = state.networks.firstOrNull { Network.sameUrl(it.url, url) }?.name

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
