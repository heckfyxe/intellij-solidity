package me.serce.solidity.chain.rpc

import me.serce.solidity.util.toHex
import me.serce.solidity.util.hexToBytes
import me.serce.solidity.util.hexToBigInt
import me.serce.solidity.util.toHexQuantity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class RpcException(message: String, val code: Int? = null, val data: String? = null) : Exception(message)

/** Minimal Ethereum JSON-RPC client over HTTP. */
class JsonRpcClient(val url: String) {
  private val ids = AtomicLong()
  /** [url] without its path and query, which often hold an API key (Infura, Alchemy): for messages and the UI. */
  val displayUrl: String = redact(url)

  fun call(method: String, vararg params: Any?): JsonNode {
    val json = post(request(method, params.toList())).takeIf { it.isObject } ?: throw RpcException("$displayUrl is not a JSON-RPC node")
    return result(json)
  }

  /**
   * Sends [calls] (method and params) as JSON-RPC batches, so many small reads cost one round trip instead of one each.
   * Returns the results in the order of [calls] and throws the first error. Nodes that don't take batches get the
   * calls one by one.
   */
  fun batch(calls: List<Pair<String, List<Any?>>>): List<JsonNode> = calls.chunked(MAX_BATCH).flatMap { chunk ->
    if (chunk.size == 1) return@flatMap listOf(call(chunk[0].first, *chunk[0].second.toTypedArray()))
    val requests = chunk.map { (method, params) -> request(method, params) }
    val responses = post(mapper.createArrayNode().addAll(requests))
    if (!responses.isArray || responses.size() != chunk.size) return@flatMap chunk.map { (method, params) -> call(method, *params.toTypedArray()) }
    val byId = responses.associateBy { it["id"]?.asLong() }
    requests.map { r -> result(byId[r["id"].asLong()] ?: throw RpcException("$displayUrl left out a batched answer")) }
  }

  private fun request(method: String, params: List<Any?>): JsonNode = mapper.createObjectNode().apply {
    put("jsonrpc", "2.0")
    put("id", ids.incrementAndGet())
    put("method", method)
    set<JsonNode>("params", mapper.valueToTree(params))
  }

  private fun result(json: JsonNode): JsonNode {
    json["error"]?.takeIf { !it.isNull }?.let { err ->
      val data = err["data"]?.let { d -> if (d.isTextual) d.asText() else d["data"]?.asText() }
      throw RpcException(err["message"]?.asText() ?: err.toString(), err["code"]?.asInt(), data)
    }
    return json["result"] ?: JsonNodeFactory.instance.nullNode()
  }

  /** POSTs [body] and returns the parsed answer. Every failure surfaces as RpcException, so callers handle one type. */
  private fun post(body: JsonNode): JsonNode {
    val builder = try {
      HttpRequest.newBuilder(URI.create(httpUrl(url)))
    } catch (e: IllegalArgumentException) {
      throw RpcException("Invalid URL: $displayUrl")
    }
    val request = builder
      .timeout(Duration.ofSeconds(60))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()
    val response = try {
      http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: java.net.ConnectException) {
      throw RpcException("Cannot connect to $displayUrl")
    } catch (e: java.io.IOException) {
      throw RpcException("Request to $displayUrl failed: ${e.message?.replace(url, displayUrl) ?: e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
      throw RpcException("Request to $displayUrl failed: ${e.message?.replace(url, displayUrl)}")
    }
    if (response.statusCode() !in 200..299) throw RpcException("HTTP ${response.statusCode()} from $displayUrl")
    return try {
      mapper.readTree(response.body())
    } catch (e: java.io.IOException) {
      throw RpcException("$displayUrl is not a JSON-RPC node")
    }
  }

  // ---- typed helpers ----

  fun chainId(): Long = call("eth_chainId").asText().hexToBigInt().toLong()
  fun blockNumber(): Long = call("eth_blockNumber").asText().hexToBigInt().toLong()
  fun accounts(): List<String> = call("eth_accounts").map { it.asText() }
  fun balance(address: String): BigInteger = call("eth_getBalance", address, "latest").asText().hexToBigInt()
  fun code(address: String): ByteArray = call("eth_getCode", address, "latest").asText().hexToBytes()

  fun ethCall(from: String?, to: String?, data: ByteArray, value: BigInteger = BigInteger.ZERO): ByteArray =
    call("eth_call", tx(from, to, data, value), "latest").asText().hexToBytes()

  fun sendTransaction(from: String, to: String?, data: ByteArray, value: BigInteger): String =
    call("eth_sendTransaction", tx(from, to, data, value)).asText()

  fun sendRawTransaction(raw: String): String = call("eth_sendRawTransaction", raw).asText()
  fun estimateGas(from: String?, to: String?, data: ByteArray, value: BigInteger): BigInteger =
    call("eth_estimateGas", tx(from, to, data, value)).asText().hexToBigInt()
  fun gasPrice(): BigInteger = call("eth_gasPrice").asText().hexToBigInt()
  fun storageAt(address: String, slot: String): ByteArray = call("eth_getStorageAt", address, slot, "latest").asText().hexToBytes()

  fun automine(): Boolean = call("anvil_getAutomine").asBoolean(false)
  fun mine() { call("evm_mine") }

  /** Polls for the receipt; anvil mines instantly by default, so this normally returns on the first try. */
  fun waitForReceipt(hash: String, timeout: Duration = Duration.ofSeconds(60)): JsonNode =
    receiptWithin(hash, timeout) ?: throw RpcException("No receipt for $hash after ${timeout.seconds}s (is mining paused?)")

  /** The receipt, or null when [hash] is still not mined after [timeout]. */
  fun receiptWithin(hash: String, timeout: Duration, pollEvery: Duration = Duration.ofMillis(200)): JsonNode? {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
      val r = call("eth_getTransactionReceipt", hash)
      if (!r.isNull) return r
      if (System.nanoTime() > deadline) return null
      Thread.sleep(pollEvery.toMillis())
    }
  }

  companion object {
    /** The transaction object of `eth_call`, `eth_sendTransaction` and `debug_traceCall`, without absent fields. */
    fun tx(from: String?, to: String?, data: ByteArray, value: BigInteger, gas: String? = null): Map<String, String> = mapOf(
      "from" to from, "to" to to, "data" to data.toHex(), "value" to value.toHexQuantity(), "gas" to gas,
    ).filterValues { it != null }.mapValues { it.value!! }

    private val mapper = ObjectMapper()
    /** Public RPCs cap batches; some at 10 calls. */
    private const val MAX_BATCH = 10
    /** One client for all chains: each HttpClient owns a selector thread. */
    private val http by lazy { ChainHttp.client(connectTimeout = Duration.ofSeconds(5)) }

    fun redact(url: String): String = runCatching {
      val u = URI.create(url)
      if (u.scheme == null || u.host == null) null
      else "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
    }.getOrNull() ?: "the RPC URL"

    /** anvil serves HTTP and WebSocket on the same port; we talk HTTP. */
    fun httpUrl(url: String): String = when {
      url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
      url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
      else -> url
    }
  }
}

