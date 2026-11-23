package me.serce.solidity.chain

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/** Chains are kept in workspace.xml: every setting must survive a save and reload of the project. */
class ChainStateSerializationTest : BasePlatformTestCase() {
  fun testNetworkChainKeepsItsNetwork() {
    val state = ChainState().apply {
      chains += ChainConfig().apply {
        kind = ChainKind.NETWORK
        name = "My Sepolia"
        network = "Sepolia"
        expectedChainId = "11155111"
        wallets += WalletConfig().apply { address = "0xC601671fb1b04de4D471593CC84002e09dea3bB8" }
      }
      chains += ChainConfig().apply {
        forkUrl = "https://example.invalid"
        forkBlock = "100"
      }
    }

    val loaded = XmlSerializer.deserialize(XmlSerializer.serialize(state), ChainState::class.java)

    val network = loaded.chains[0]
    assertEquals(ChainKind.NETWORK, network.kind)
    assertEquals("Sepolia", network.network)
    assertEquals("11155111", network.expectedChainId)
    assertEquals("0xC601671fb1b04de4D471593CC84002e09dea3bB8", network.wallets.single().address)
    assertTrue(network.isNetworkChain)
    val fork = loaded.chains[1]
    assertEquals("https://example.invalid", fork.forkUrl)
    assertEquals("100", fork.forkBlock)
    assertTrue(fork.isFork)
  }
}
