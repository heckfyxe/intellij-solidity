package me.serce.solidity.chain.compile

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import me.serce.solidity.chain.foundry.ForgeConfig
import me.serce.solidity.chain.foundry.SolcLocator.Version

/** Builds the solc standard-json input the live annotator sends, with the compiler settings forge would use. */
object SolcRequest {
  private val mapper = ObjectMapper()

  /** The first solc release that accepts each `evmVersion`, as in foundry-compilers' normalization. */
  private val EVM_VERSIONS = listOf(
    "homestead" to Version(0, 4, 0),
    "tangerineWhistle" to Version(0, 4, 0),
    "spuriousDragon" to Version(0, 4, 0),
    "byzantium" to Version(0, 4, 21),
    "constantinople" to Version(0, 4, 21),
    "petersburg" to Version(0, 5, 5),
    "istanbul" to Version(0, 5, 14),
    "berlin" to Version(0, 8, 5),
    "london" to Version(0, 8, 7),
    "paris" to Version(0, 8, 18),
    "shanghai" to Version(0, 8, 20),
    "cancun" to Version(0, 8, 24),
    "prague" to Version(0, 8, 27),
    "osaka" to Version(0, 8, 29),
  )

  /**
   * Lowers [evmVersion] to the newest one [solc] supports, like forge does (its default `osaka` becomes
   * `cancun` for solc 0.8.24). Unknown names, or an unknown solc version, are passed through unchanged.
   */
  fun normalizeEvmVersion(evmVersion: String, solc: Version?): String {
    if (solc == null) return evmVersion
    val index = EVM_VERSIONS.indexOfFirst { it.first.equals(evmVersion, ignoreCase = true) }
    if (index < 0) return evmVersion
    return EVM_VERSIONS.subList(0, index + 1).lastOrNull { solc >= it.second }?.first ?: evmVersion
  }

  /** The version of an svm-installed binary, `.../0.8.24/solc-0.8.24`. */
  fun versionOf(solcPath: String): Version? =
    Regex("""solc-v?(\d+\.\d+\.\d+)""").find(solcPath.substringAfterLast('/').substringAfterLast('\\'))
      ?.groupValues?.get(1)?.let { Version.parse(it) }

  /** An analysis-only request (empty output selection) for [relativePath] plus the other unsaved sources. */
  fun build(
    relativePath: String,
    text: String,
    unsaved: Map<String, String>,
    config: ForgeConfig,
    solcVersion: Version?,
    withEvmVersion: Boolean = true,
  ): ObjectNode {
    val sources = mapper.createObjectNode()
    sources.putObject(relativePath).put("content", text)
    unsaved.forEach { (path, content) -> sources.putObject(path).put("content", content) }
    return mapper.createObjectNode().apply {
      put("language", "Solidity")
      set<JsonNode>("sources", sources)
      putObject("settings").apply {
        putArray("remappings").apply { config.remappings.forEach { add(it) } }
        if (withEvmVersion) config.evmVersion?.let { put("evmVersion", normalizeEvmVersion(it, solcVersion)) }
        if (config.viaIr) put("viaIR", true)
        putObject("optimizer").apply {
          put("enabled", config.optimizer)
          put("runs", config.optimizerRuns)
        }
        putObject("outputSelection") // nothing: solc stops after analysis
      }
    }
  }
}
