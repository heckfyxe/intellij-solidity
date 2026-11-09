package me.serce.solidity.chain.rpc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import me.serce.solidity.chain.abi.hexToBytes
import me.serce.solidity.chain.abi.toHex
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class RpcException(message: String, val code: Int? = null, val data: String? = null) : Exception(message)

/** Minimal Ethereum JSON-RPC client over HTTP. */
class JsonRpcClient(val url: String) {
  private val ids = AtomicLong()

  fun call(method: String, vararg params: Any?): JsonNode {
    val body = mapper.createObjectNode().apply {
      put("jsonrpc", "2.0")
      put("id", ids.incrementAndGet())
      put("method", method)
      set<JsonNode>("params", mapper.valueToTree(params.toList()))
    }
    // Every failure surfaces as RpcException, so callers (connect, refresh, transactions) handle one type.
    val builder = try {
      HttpRequest.newBuilder(URI.create(httpUrl(url)))
    } catch (e: IllegalArgumentException) {
      throw RpcException("Invalid URL: $url")
    }
    val request = builder
      .timeout(Duration.ofSeconds(60))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()
    val response = try {
      http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: java.net.ConnectException) {
      throw RpcException("Cannot connect to $url")
    } catch (e: java.io.IOException) {
      throw RpcException("Request to $url failed: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
      throw RpcException("Request to $url failed: ${e.message}")
    }
    if (response.statusCode() !in 200..299) throw RpcException("HTTP ${response.statusCode()} from $url")
    val json = try {
      mapper.readTree(response.body()).takeIf { it.isObject } ?: throw RpcException("$url is not a JSON-RPC node")
    } catch (e: java.io.IOException) {
      throw RpcException("$url is not a JSON-RPC node")
    }
    json["error"]?.takeIf { !it.isNull }?.let { err ->
      val data = err["data"]?.let { d -> if (d.isTextual) d.asText() else d["data"]?.asText() }
      throw RpcException(err["message"]?.asText() ?: err.toString(), err["code"]?.asInt(), data)
    }
    return json["result"] ?: JsonNodeFactory.instance.nullNode()
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

  fun automine(): Boolean = call("anvil_getAutomine").asBoolean(false)
  fun mine() { call("evm_mine") }

  /** Polls for the receipt; anvil mines instantly by default, so this normally returns on the first try. */
  fun waitForReceipt(hash: String, timeout: Duration = Duration.ofSeconds(60)): JsonNode =
    receiptWithin(hash, timeout) ?: throw RpcException("No receipt for $hash after ${timeout.seconds}s (is mining paused?)")

  /** The receipt, or null when [hash] is still not mined after [timeout]. */
  fun receiptWithin(hash: String, timeout: Duration): JsonNode? {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
      val r = call("eth_getTransactionReceipt", hash)
      if (!r.isNull) return r
      if (System.nanoTime() > deadline) return null
      Thread.sleep(200)
    }
  }

  private fun tx(from: String?, to: String?, data: ByteArray, value: BigInteger) = mapOf(
    "from" to from, "to" to to, "data" to data.toHex(), "value" to "0x" + value.toString(16),
  ).filterValues { it != null }

  companion object {
    private val mapper = ObjectMapper()
    /** One client for all chains: each HttpClient owns a selector thread. */
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** anvil serves HTTP and WebSocket on the same port; we talk HTTP. */
    fun httpUrl(url: String): String = when {
      url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
      url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
      else -> url
    }
  }
}

fun String.hexToBigInt(): BigInteger = removePrefix("0x").ifEmpty { "0" }.toBigInteger(16)
