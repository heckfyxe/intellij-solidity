package me.serce.solidity.chain.abi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.rpc.RpcException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AbiFetchException(message: String) : Exception(message)

/** A verified ABI found for an address. [implementation] is set when the address is a proxy and the ABI is its implementation's. */
class FetchedAbi(val json: String, val name: String?, val source: String, val implementation: String? = null)

/**
 * Finds the ABI of a verified contract: Sourcify first (no key needed), then Etherscan's multichain API when the user
 * has an API key. Proxies (EIP-1967, including beacon proxies) get their implementation's ABI, with the proxy's own
 * functions (upgradeTo, admin, …) added. Blocks on the network; call off the EDT.
 */
class AbiFetcher(
  private val etherscanKey: () -> String?,
  /** GET [url] and return (status, body). */
  private val get: (url: String) -> Pair<Int, String> = ::httpGet,
) {
  private class Found(val abi: ArrayNode, val name: String?, val source: String, val implementation: String?)

  fun fetch(rpc: JsonRpcClient?, chainId: Long, address: String): FetchedAbi {
    val own = find(chainId, address)
    val implementation = rpc?.let { proxyImplementation(it, address) } ?: own?.implementation
    if (implementation != null && !implementation.equals(address, ignoreCase = true)) {
      val impl = find(chainId, implementation)
      if (impl != null) {
        val merged = mapper.createArrayNode().addAll(impl.abi)
        val seen = impl.abi.map(::key).toHashSet()
        own?.abi?.filter { key(it) !in seen }?.forEach { merged.add(it) }
        return FetchedAbi(mapper.writeValueAsString(merged), impl.name ?: own?.name, impl.source, AbiCodec.checksum(implementation))
      }
    }
    if (own != null) return FetchedAbi(mapper.writeValueAsString(own.abi), own.name, own.source)
    val where = if (etherscanKey() == null) "Sourcify (add an Etherscan API key to search Etherscan too)" else "Sourcify or Etherscan"
    throw AbiFetchException("No verified contract at $address on $where" +
      (implementation?.let { ", nor at its implementation $it" } ?: "") + ". Paste its ABI instead.")
  }

  private fun find(chainId: Long, address: String): Found? = sourcify(chainId, address) ?: etherscan(chainId, address)

  private fun sourcify(chainId: Long, address: String): Found? {
    val (status, body) = request("https://sourcify.dev/server/v2/contract/$chainId/$address?fields=abi,compilation", "Sourcify")
    if (status == 404) return null
    if (status !in 200..299) throw AbiFetchException("Sourcify answered HTTP $status")
    val node = parse(body, "Sourcify")
    val abi = node["abi"] as? ArrayNode ?: return null
    return Found(abi, node["compilation"]?.get("name")?.asText(), "Sourcify", null)
  }

  private fun etherscan(chainId: Long, address: String): Found? {
    val key = etherscanKey() ?: return null
    val url = "https://api.etherscan.io/v2/api?chainid=$chainId&module=contract&action=getsourcecode&address=$address&apikey=" +
      URLEncoder.encode(key, Charsets.UTF_8)
    val (status, body) = request(url, "Etherscan")
    if (status !in 200..299) throw AbiFetchException("Etherscan answered HTTP $status")
    val node = parse(body, "Etherscan")
    val result = node["result"]
    if (node["status"]?.asText() != "1" || result !is ArrayNode) {
      // e.g. "Invalid API Key", rate limits; drop the key in case the message echoes it
      val reason = (result?.takeIf { it.isTextual }?.asText() ?: node["message"]?.asText() ?: "no result").replace(key, "…").take(200)
      throw AbiFetchException("Etherscan: $reason")
    }
    val entry = result.firstOrNull() ?: return null
    val abi = runCatching { mapper.readTree(entry["ABI"]?.asText().orEmpty()) }.getOrNull() as? ArrayNode ?: return null // "Contract source code not verified"
    val implementation = entry["Implementation"]?.asText()?.takeIf { entry["Proxy"]?.asText() == "1" && it.length == 42 }
    return Found(abi, entry["ContractName"]?.asText()?.takeIf { it.isNotEmpty() }, "Etherscan", implementation)
  }

  private fun request(url: String, service: String): Pair<Int, String> = try {
    get(url)
  } catch (e: IOException) {
    throw AbiFetchException("Cannot reach $service: ${e.message ?: e.javaClass.simpleName}")
  }

  private fun parse(body: String, service: String): JsonNode = try {
    mapper.readTree(body)
  } catch (e: IOException) {
    throw AbiFetchException("$service answered something that is not JSON")
  }

  companion object {
    private val mapper = ObjectMapper()
    /** `bytes32(uint256(keccak256("eip1967.proxy.implementation")) - 1)` */
    const val IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc"
    /** `bytes32(uint256(keccak256("eip1967.proxy.beacon")) - 1)` */
    const val BEACON_SLOT = "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50"
    private val IMPLEMENTATION_SELECTOR = Keccak.hash("implementation()").copyOf(4)

    /** The implementation behind an EIP-1967 proxy at [address], or null when it isn't one. */
    fun proxyImplementation(rpc: JsonRpcClient, address: String): String? = try {
      addressIn(rpc.storageAt(address, IMPLEMENTATION_SLOT)) ?: addressIn(rpc.storageAt(address, BEACON_SLOT))?.let { beacon ->
        addressIn(rpc.ethCall(null, beacon, IMPLEMENTATION_SELECTOR))
      }
    } catch (e: RpcException) {
      null
    }

    private fun addressIn(word: ByteArray): String? {
      if (word.size < 32 || word.all { it.toInt() == 0 }) return null
      return AbiCodec.checksum(word.copyOfRange(word.size - 20, word.size).toHex())
    }

    /** Identifies an ABI entry by kind, name and parameter types, to merge a proxy's ABI with its implementation's. */
    private fun key(entry: JsonNode): String =
      "${entry["type"]?.asText()}:${entry["name"]?.asText()}(${entry["inputs"]?.joinToString(",") { it["type"]?.asText().orEmpty() }})"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    private fun httpGet(url: String): Pair<Int, String> {
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build()
      val response = try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (e: InterruptedException) {
        throw IOException("interrupted")
      }
      return response.statusCode() to response.body()
    }
  }
}
