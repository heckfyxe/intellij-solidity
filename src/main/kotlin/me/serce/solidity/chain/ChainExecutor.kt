package me.serce.solidity.chain

import com.fasterxml.jackson.databind.JsonNode
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.abi.AbiEntry
import me.serce.solidity.chain.abi.AbiParam
import me.serce.solidity.chain.abi.AbiType
import me.serce.solidity.chain.abi.AbiValueFormatter
import me.serce.solidity.chain.abi.ContractAbi
import me.serce.solidity.chain.abi.RevertDecoder
import me.serce.solidity.chain.abi.hexToBytes
import me.serce.solidity.chain.abi.toHex
import me.serce.solidity.chain.debug.CompiledSources
import me.serce.solidity.chain.debug.RevertTracer
import me.serce.solidity.chain.debug.TraceTarget
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.rpc.RpcException
import me.serce.solidity.chain.rpc.hexToBigInt
import java.math.BigInteger
import java.time.Duration

/** Runs deploys, calls and transactions against a running chain and records them. Call off the EDT. */
class ChainExecutor(
  private val service: ChainService,
  /** Where compiled artifacts come from, to name the source line a failure happened at. */
  private val foundry: () -> FoundryProject? = { FoundryProject.find(service.project) },
) {

  class Outcome(val record: TxRecord, val deployment: Deployment?)

  fun deploy(chain: ChainConfig, artifact: Artifact, args: List<Any>, from: String, value: BigInteger): Outcome {
    val ctor = artifact.abi.constructor
    val record = record(chain, TxKind.DEPLOY, artifact.name, artifact.sourcePath, AbiEntry.signature("constructor", ctor?.inputs.orEmpty()), from, "", value)
    record.args += formatArgs(ctor?.inputs.orEmpty(), args)
    val data = artifact.bytecode.hexToBytes() + AbiCodec.encode(ctor?.inputs.orEmpty().map { it.type }, args)
    var deployment: Deployment? = null
    run(chain, record, artifact.abi, TraceTarget.Call(from, null, data, value)) { rpc ->
      val hash = rpc.sendTransaction(from, null, data, value)
      val receipt = awaitReceipt(chain, rpc, hash)
      applyReceipt(rpc, record, receipt, artifact.abi)
      if (record.status == TxStatus.SUCCESS) {
        val address = AbiCodec.checksum(receipt["contractAddress"].asText())
        record.to = address
        record.results += "address: $address"
        deployment = Deployment().also {
          it.chainId = chain.id
          it.name = artifact.name
          it.sourcePath = artifact.sourcePath
          it.address = address
          it.abiJson = artifact.abiJson
        }
      }
    }
    deployment?.let { service.addDeployment(it) }
    service.addRecord(record)
    service.refresh(chain)
    return Outcome(record, deployment)
  }

  fun invoke(chain: ChainConfig, target: Deployment, fn: AbiEntry.Function, args: List<Any>, from: String, value: BigInteger): Outcome {
    val abi = target.abi()
    val kind = if (fn.mutability.isReadOnly) TxKind.CALL else TxKind.TRANSACT
    val record = record(chain, kind, target.name, target.sourcePath, fn.signature, from, target.address, value)
    record.args += formatArgs(fn.inputs, args)
    val data = AbiCodec.encodeCall(fn.selector, fn.inputs.map { it.type }, args)
    run(chain, record, abi, TraceTarget.Call(from, target.address, data, value)) { rpc ->
      // Calling an address without code "succeeds" and returns nothing, e.g. after the chain it was deployed on restarted.
      if (rpc.code(target.address).isEmpty()) {
        record.status = TxStatus.FAILED
        record.error = "There is no contract at ${target.address} on ${chain.name}. " +
          "It may have been deployed to a chain that has since restarted; deploy it again."
        return@run
      }
      // Simulate first: gives the return value of state-changing functions and the revert reason before sending.
      val returned = rpc.ethCall(from, target.address, data, value)
      record.results += formatOutputs(fn.outputs, returned)
      if (kind == TxKind.TRANSACT) {
        val hash = rpc.sendTransaction(from, target.address, data, value)
        applyReceipt(rpc, record, awaitReceipt(chain, rpc, hash), abi)
      }
    }
    service.addRecord(record)
    if (kind == TxKind.TRANSACT) service.refresh(chain)
    return Outcome(record, null)
  }

  private fun run(chain: ChainConfig, record: TxRecord, abi: ContractAbi, target: TraceTarget, body: (JsonRpcClient) -> Unit) {
    val rpc = service.runtime(chain.id).rpc
    if (rpc == null) {
      record.status = TxStatus.FAILED
      record.error = "${chain.name} is not running"
      return
    }
    try {
      body(rpc)
    } catch (rpcError: RpcException) {
      val e = explainForkFailure(chain, rpcError)
      val revertData = e.data?.takeIf { it.startsWith("0x") }
      val message = e.message.orEmpty()
      record.status = if (revertData != null || message.contains("revert", ignoreCase = true)) TxStatus.REVERTED else TxStatus.FAILED
      record.error = revertData?.let { decodeRevert(it.hexToBytes(), abi) } ?: message
      explain(rpc, record, abi, target) // also EVM failures without revert data: out of gas, invalid opcode
    } catch (e: RuntimeException) { // bad input encoding, or return data that doesn't match the ABI
      record.status = TxStatus.FAILED
      record.error = e.message ?: e.toString()
    }
  }

  /**
   * anvil mines each transaction as it arrives unless told otherwise, so one still pending a moment later means mining
   * failed, which anvil only logs. Mining once by hand returns that error instead of leaving the transaction stuck.
   */
  private fun awaitReceipt(chain: ChainConfig, rpc: JsonRpcClient, hash: String): JsonNode {
    if (chain.kind == ChainKind.ANVIL) {
      rpc.receiptWithin(hash, Duration.ofSeconds(2))?.let { return it }
      if (rpc.automine()) rpc.mine()
    }
    return rpc.waitForReceipt(hash)
  }

  /**
   * A fork reads the forked network's state at the fork block from its RPC. Most RPCs are not archive nodes and drop
   * that state after about 128 blocks (~25 minutes on mainnet); from then on no block can be mined, and calls touching
   * state anvil has not cached fail too. anvil reports this as a "database error" wrapping the RPC's own message.
   */
  private fun explainForkFailure(chain: ChainConfig, e: RpcException): RpcException {
    val message = e.message.orEmpty()
    if (!chain.isFork || !message.contains("database error")) return e
    val upstream = message.substringAfterLast("error response:").trim().ifEmpty { message }
    if (STALE_STATE.none { message.contains(it, ignoreCase = true) }) {
      return RpcException("anvil could not read the forked network: $upstream")
    }
    service.notifyStaleFork(chain)
    return RpcException(
      "The fork RPC no longer serves the state of the fork block, so ${chain.name} cannot mine or read new state. " +
        "Restart the chain to fork again from the latest block, or fork from an archive RPC. ($upstream)"
    )
  }

  /**
   * Adds where the failure happened, and what else went wrong on the way, by re-running it with the node's tracer.
   * Nodes without `debug_trace*` (most public RPCs) just leave the decoded reason.
   */
  private fun explain(rpc: JsonRpcClient, record: TxRecord, abi: ContractAbi, target: TraceTarget) {
    val trace = try {
      RevertTracer(rpc, compiledSources()).trace(target)
    } catch (e: RpcException) {
      null
    } catch (e: RuntimeException) {
      null
    } ?: return
    if (target is TraceTarget.Mined) trace.revertData?.let { record.error = decodeRevert(it, abi) }
    record.errorFrames = trace.frames.mapTo(ArrayList()) { ErrorFrame.of(it) }
    record.notes += trace.notes
    if (record.error == RevertDecoder.NO_REASON && record.notes.isEmpty() && record.errorFrames.none { it.path.isNotEmpty() }) {
      record.notes += "A revert without a reason comes from require() or revert() without a message, a failed call whose " +
        "error was passed on empty, or decoding return data that is empty or malformed."
    }
  }

  private fun compiledSources(): CompiledSources? = runCatching {
    foundry()?.let { CompiledSources(it.root, it.root.resolve(it.config().out)) }
  }.getOrNull()

  /** Decodes revert data with the errors of known deployments first, then of every compiled contract. */
  private fun decodeRevert(data: ByteArray, abi: ContractAbi): String {
    val known = knownErrors(abi)
    RevertDecoder.decodeOrNull(data, known)?.let { return it }
    val compiled = runCatching { foundry()?.artifacts()?.flatMap { it.abi.errors } }.getOrNull().orEmpty()
    return RevertDecoder.decode(data, compiled.ifEmpty { known })
  }

  private fun applyReceipt(rpc: JsonRpcClient, record: TxRecord, receipt: JsonNode, abi: ContractAbi) {
    record.txHash = receipt["transactionHash"]?.asText().orEmpty()
    record.blockNumber = receipt.quantity("blockNumber")
    record.gasUsed = receipt.quantity("gasUsed")
    if (receipt["status"]?.asText() == "0x0") {
      record.status = TxStatus.REVERTED
      if (record.error.isEmpty()) record.error = "transaction reverted"
      explain(rpc, record, abi, TraceTarget.Mined(record.txHash))
    }
    val known = service.allDeployments()
    receipt["logs"]?.forEach { record.events += decodeLog(it, abi, known) }
  }

  private fun JsonNode.quantity(field: String) = this[field]?.asText()?.hexToBigInt()?.toString().orEmpty()

  /** Decodes a log with the ABI of whichever known contract emitted it, falling back to [abi]. */
  private fun decodeLog(log: JsonNode, abi: ContractAbi, known: List<Deployment>): String {
    val address = log["address"]?.asText().orEmpty()
    val topics = log["topics"]?.map { it.asText() } ?: emptyList()
    val data = log["data"]?.asText()?.hexToBytes() ?: ByteArray(0)
    val emitterAbi = known.firstOrNull { it.address.equals(address, ignoreCase = true) }?.abi()
    val candidates = listOfNotNull(emitterAbi, abi).flatMap { it.events }
    val event = candidates.firstOrNull { !it.anonymous && topics.firstOrNull() == it.topic.toHex() }
      ?: return "log from ${short(address)} topics=${topics.joinToString()} data=${data.toHex()}"
    return try {
      val plain = event.inputs.filter { !it.indexed }
      val plainValues = AbiCodec.decode(plain.map { it.type }, data).iterator()
      val indexedTopics = topics.drop(1).iterator()
      val parts = event.inputs.map { p ->
        val v: Any = if (p.indexed) {
          val topic = indexedTopics.next().hexToBytes()
          if (p.type.isDynamic || p.type is AbiType.Tuple || p.type is AbiType.Array) "hash ${topic.toHex()}"
          else AbiCodec.decode(listOf(p.type), topic)[0]
        } else plainValues.next()
        (if (p.name.isNotEmpty()) "${p.name}: " else "") + AbiValueFormatter.format(v)
      }
      "${event.name}(${parts.joinToString(", ")})"
    } catch (e: Exception) {
      "${event.name} (could not decode: ${e.message})"
    }
  }

  private fun knownErrors(abi: ContractAbi): List<AbiEntry.Error> =
    abi.errors + service.allDeployments().flatMap { it.abi().errors }

  private fun record(chain: ChainConfig, kind: TxKind, contract: String, source: String, function: String, from: String, to: String, value: BigInteger) =
    TxRecord().also {
      it.chainId = chain.id
      it.kind = kind
      it.contractName = contract
      it.sourcePath = source
      it.function = function
      it.from = from
      it.to = to
      it.valueWei = value.toString()
    }

  companion object {
    /** How RPCs (geth, reth, erigon, public endpoints, anvil itself) say they no longer have a block's state. */
    private val STALE_STATE = listOf("historical state", "missing trie node", "header not found", "pruned", "BlockOutOfRange", "not available")

    fun formatArgs(params: List<AbiParam>, values: List<Any>): List<String> =
      params.zip(values).mapIndexed { i, (p, v) -> "${p.name.ifEmpty { "arg$i" }}: ${formatValue(v)}" }

    fun formatOutputs(params: List<AbiParam>, data: ByteArray): List<String> {
      if (params.isEmpty()) return emptyList()
      val values = AbiCodec.decode(params.map { it.type }, data)
      return params.zip(values).mapIndexed { i, (p, v) ->
        val label = p.name.ifEmpty { if (params.size > 1) "[$i]" else "" }
        (if (label.isEmpty()) "" else "$label: ") + "${p.displayType} ${formatValue(v)}"
      }
    }

    fun formatValue(v: Any): String {
      val base = AbiValueFormatter.format(v)
      val readable = (v as? BigInteger)?.let { AbiValueFormatter.readable(it) }
      return if (readable != null) "$base ($readable)" else base
    }

    fun short(address: String) = if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address
  }
}
