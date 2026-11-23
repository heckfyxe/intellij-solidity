package me.serce.solidity.chain

/** What we know about public chains by id: whether real money is at stake, and where to look things up. */
object KnownChains {
  class Info(val name: String, val explorer: String, val mainnet: Boolean)

  private val chains = mapOf(
    1L to Info("Ethereum Mainnet", "https://etherscan.io", mainnet = true),
    11155111L to Info("Sepolia", "https://sepolia.etherscan.io", mainnet = false),
    560048L to Info("Hoodi", "https://hoodi.etherscan.io", mainnet = false),
    17000L to Info("Holesky", "https://holesky.etherscan.io", mainnet = false),
    8453L to Info("Base", "https://basescan.org", mainnet = true),
    84532L to Info("Base Sepolia", "https://sepolia.basescan.org", mainnet = false),
    42161L to Info("Arbitrum One", "https://arbiscan.io", mainnet = true),
    421614L to Info("Arbitrum Sepolia", "https://sepolia.arbiscan.io", mainnet = false),
    10L to Info("Optimism", "https://optimistic.etherscan.io", mainnet = true),
    11155420L to Info("OP Sepolia", "https://sepolia-optimism.etherscan.io", mainnet = false),
    137L to Info("Polygon", "https://polygonscan.com", mainnet = true),
    80002L to Info("Polygon Amoy", "https://amoy.polygonscan.com", mainnet = false),
    56L to Info("BNB Smart Chain", "https://bscscan.com", mainnet = true),
    97L to Info("BNB Testnet", "https://testnet.bscscan.com", mainnet = false),
    43114L to Info("Avalanche C-Chain", "https://snowtrace.io", mainnet = true),
    43113L to Info("Avalanche Fuji", "https://testnet.snowtrace.io", mainnet = false),
  )

  fun info(chainId: Long?): Info? = chainId?.let { chains[it] }

  /**
   * Unknown chain ids count as mainnets: confirming once too often is cheaper than spending real money unasked.
   * Local dev chain ids (anvil's 31337, hardhat's 1337) are not.
   */
  fun isMainnet(chainId: Long?): Boolean = when (chainId) {
    null -> true
    31337L, 1337L -> false
    else -> chains[chainId]?.mainnet ?: true
  }

  fun addressUrl(chainId: Long?, address: String) = info(chainId)?.let { "${it.explorer}/address/$address" }
  fun txUrl(chainId: Long?, hash: String) = info(chainId)?.let { "${it.explorer}/tx/$hash" }
}
