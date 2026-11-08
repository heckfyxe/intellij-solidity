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
import me.serce.solidity.chain.foundry.Artifact
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.rpc.RpcException
import me.serce.solidity.chain.rpc.hexToBigInt
import java.math.BigInteger

/** Runs deploys, calls and transactions against a running chain and records them. Call off the EDT. */
class ChainExecutor(private val service: ChainService) {

  class Outcome(val record: TxRecord, val deployment: Deployment?)

  fun deploy(chain: ChainConfig, artifact: Artifact, args: List<Any>, from: String, value: BigInteger): Outcome {
    val ctor = artifact.abi.constructor
    val record = record(chain, TxKind.DEPLOY, artifact.name, artifact.sourcePath, AbiEntry.signature("constructor", ctor?.inputs.orEmpty()), from, "", value)
    record.args += formatArgs(ctor?.inputs.orEmpty(), args)
    val data = artifact.bytecode.hexToBytes() + AbiCodec.encode(ctor?.inputs.orEmpty().map { it.type }, args)
    var deployment: Deployment? = null
    run(chain, record, artifact.abi) { rpc ->
      val hash = rpc.sendTransaction(from, null, data, value)
      val receipt = rpc.waitForReceipt(hash)
      applyReceipt(record, receipt, artifact.abi)
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
          it.deployTx = hash
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
    run(chain, record, abi) { rpc ->
      // Simulate first: gives the return value of state-changing functions and the revert reason before sending.
      val returned = rpc.ethCall(from, target.address, data, value)
      record.results += formatOutputs(fn.outputs, returned)
      if (kind == TxKind.TRANSACT) {
        val hash = rpc.sendTransaction(from, target.address, data, value)
        applyReceipt(record, rpc.waitForReceipt(hash), abi)
      }
    }
    service.addRecord(record)
    if (kind == TxKind.TRANSACT) service.refresh(chain)
    return Outcome(record, null)
  }

  private fun run(chain: ChainConfig, record: TxRecord, abi: ContractAbi, body: (JsonRpcClient) -> Unit) {
    val rpc = service.runtime(chain.id).rpc
    if (rpc == null) {
      record.status = TxStatus.FAILED
      record.error = "${chain.name} is not running"
      return
    }
    try {
      body(rpc)
    } catch (e: RpcException) {
      val revertData = e.data?.takeIf { it.startsWith("0x") }
      val message = e.message.orEmpty()
      record.status = if (revertData != null || message.contains("revert", ignoreCase = true)) TxStatus.REVERTED else TxStatus.FAILED
      record.error = revertData?.let { RevertDecoder.decode(it.hexToBytes(), knownErrors(abi)) } ?: message
    } catch (e: RuntimeException) { // bad input encoding, or return data that doesn't match the ABI
      record.status = TxStatus.FAILED
      record.error = e.message ?: e.toString()
    }
  }

  private fun applyReceipt(record: TxRecord, receipt: JsonNode, abi: ContractAbi) {
    record.txHash = receipt["transactionHash"]?.asText().orEmpty()
    record.blockNumber = receipt["blockNumber"]?.asText()?.hexToBigInt()?.toString().orEmpty()
    record.gasUsed = receipt["gasUsed"]?.asText()?.hexToBigInt()?.toString().orEmpty()
    if (receipt["status"]?.asText() == "0x0") {
      record.status = TxStatus.REVERTED
      if (record.error.isEmpty()) record.error = "transaction reverted"
    }
    val known = service.allDeployments()
    receipt["logs"]?.forEach { record.events += decodeLog(it, abi, known) }
  }

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
