package me.serce.solidity.chain.debug

import com.fasterxml.jackson.databind.JsonNode
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.abi.hexToBytes
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.rpc.hexToBigInt
import me.serce.solidity.chain.rpc.toHexQuantity
import java.math.BigInteger

/** What failed: a call simulated against the latest block, or a transaction that was mined and reverted. */
sealed class TraceTarget {
  class Call(val from: String, val to: String?, val data: ByteArray, val value: BigInteger) : TraceTarget()
  class Mined(val hash: String) : TraceTarget()
}

/** One contract on the way from the transaction to where it failed. */
class FailedFrame(val contract: String?, val address: String?, val location: SourceLocation?)

class FailureTrace(
  /** What the transaction reverted with; empty for a revert without a reason, null when it did not revert. */
  val revertData: ByteArray?,
  /** Where it failed first, then each caller up to the contract that was called. */
  val frames: List<FailedFrame>,
  /** Facts that explain a failure the revert data alone doesn't, such as calls to addresses without code. */
  val notes: List<String>,
)

/**
 * Re-runs a failed call or transaction with the node's tracer (anvil, geth, reth support `debug_trace*`), to show
 * where it failed even when it reverted without a reason. Returns null when the node has no tracer.
 */
class RevertTracer(private val rpc: JsonRpcClient, private val sources: CompiledSources?) {
  private val codes = HashMap<String, ByteArray>()

  fun trace(target: TraceTarget): FailureTrace? {
    val replay = replayOf(target)
    var steps = request(target, replay, STEP_OPTIONS)
    // anvil keeps the steps of mined transactions only with --steps-tracing; replaying on the parent block is the same run
    if (target is TraceTarget.Mined && steps["structLogs"]?.isEmpty != false) steps = traceCall(replay, STEP_OPTIONS)
    if (steps["failed"]?.asBoolean() != true) return null // not an EVM failure: a bad nonce, no funds, a node error
    val revertData = steps["returnValue"]?.asText()?.let { runCatching { it.hexToBytes() }.getOrNull() }
    val to = replay.call.to
    val initCode = replay.call.data.takeIf { to == null }
    val root = FrameAnalysis.build(parseSteps(steps["structLogs"]), to)
    val frames = root.failurePath().asReversed().map { frame ->
      val compiled = when {
        frame.address != null -> sources?.runtime(code(frame.address))
        frame === root && initCode != null -> sources?.creation(initCode)
        else -> null // a contract created inside the call: its creation code isn't on chain anywhere
      }
      FailedFrame(compiled?.contract, frame.address, compiled?.locate(frame.pcs))
    }

    val notes = LinkedHashSet<String>()
    val missing = LinkedHashSet<String>()
    root.failurePath().forEach { missing += it.checkedWithoutCode }
    runCatching { request(target, replay, mapOf("tracer" to "callTracer")) }.getOrNull()?.let { calls ->
      collectCallNotes(calls, isRoot = true, missing, notes)
    }
    val described = missing.map { "No contract at $it: it has no code, so calls to it return no data" }
    return FailureTrace(revertData, frames, described + notes)
  }

  private fun collectCallNotes(call: JsonNode, isRoot: Boolean, missing: MutableSet<String>, notes: MutableSet<String>) {
    val type = call["type"]?.asText().orEmpty()
    val to = call["to"]?.asText()
    val input = call["input"]?.asText().orEmpty()
    if (!isRoot && type in CALL_OPS && to != null && input.length > 2 && !isPrecompile(to) && code(to).isEmpty()) {
      missing += AbiCodec.checksum(to)
    }
    call["error"]?.asText()?.takeIf { it.isNotEmpty() && !it.contains("revert", ignoreCase = true) }?.let { error ->
      val who = to?.let { address -> sources?.runtime(code(address))?.contract ?: AbiCodec.checksum(address) }
      notes += if (who != null) "$who failed: $error" else "Failed: $error"
    }
    call["calls"]?.forEach { collectCallNotes(it, isRoot = false, missing, notes) }
  }

  /** The call to re-run and the block whose state it runs on: the latest, or the one before a mined transaction. */
  private class Replay(val call: TraceTarget.Call, val block: String, val gas: String?)

  private fun replayOf(target: TraceTarget): Replay = when (target) {
    is TraceTarget.Call -> Replay(target, "latest", null)
    is TraceTarget.Mined -> {
      val tx = rpc.call("eth_getTransactionByHash", target.hash)
      val call = TraceTarget.Call(
        from = tx["from"].asText(),
        to = tx["to"]?.takeIf { !it.isNull }?.asText(),
        data = tx["input"]?.asText()?.hexToBytes() ?: ByteArray(0),
        value = tx["value"]?.asText()?.hexToBigInt() ?: BigInteger.ZERO,
      )
      val parent = tx["blockNumber"]?.asText()?.removePrefix("0x")?.toBigIntegerOrNull(16)?.minus(BigInteger.ONE)?.max(BigInteger.ZERO)
      Replay(call, parent?.toHexQuantity() ?: "latest", tx["gas"]?.asText())
    }
  }

  private fun request(target: TraceTarget, replay: Replay, options: Map<String, Any>): JsonNode = when (target) {
    is TraceTarget.Call -> traceCall(replay, options)
    is TraceTarget.Mined -> rpc.call("debug_traceTransaction", target.hash, options)
  }

  private fun traceCall(replay: Replay, options: Map<String, Any>): JsonNode =
    rpc.call("debug_traceCall", replay.call.run { JsonRpcClient.tx(from, to, data, value, replay.gas) }, replay.block, options)

  private fun code(address: String): ByteArray = codes.getOrPut(address.lowercase()) { runCatching { rpc.code(address) }.getOrDefault(ByteArray(0)) }

  companion object {
    private val STEP_OPTIONS = mapOf("disableMemory" to true, "disableStorage" to true, "enableReturnData" to false)

    /** Precompiles (ecrecover, sha256, …, p256verify at 0x100) run without code. */
    fun isPrecompile(address: String): Boolean =
      runCatching { BigInteger(address.removePrefix("0x"), 16) <= BigInteger.valueOf(0x1ff) }.getOrDefault(false)

    fun parseSteps(logs: JsonNode?): List<Step> = logs?.map { log ->
      val stack = log["stack"]
      Step(
        depth = log["depth"].asInt(),
        pc = log["pc"].asInt(),
        op = log["op"].asText(),
        // the top of the stack is the last element; two values are all we read
        stackTop = stack?.takeIf { it.isArray }?.let { s -> (s.size() - 1 downTo maxOf(0, s.size() - 2)).map { s[it].asText() } }.orEmpty(),
      )
    }.orEmpty()
  }
}

/** Opcodes that enter the code of another contract; they take the address as their second argument. */
private val CALL_OPS = setOf("CALL", "STATICCALL", "DELEGATECALL", "CALLCODE")

/** One executed instruction from `structLogs`, with the two topmost stack values (top first). */
data class Step(val depth: Int, val pc: Int, val op: String, val stackTop: List<String>)

/** A contract's execution inside a transaction: the call into it, the instructions it ran, and the calls it made. */
class Frame(val address: String?) {
  val pcs = ArrayList<Int>()
  val children = ArrayList<Frame>()
  var lastOp = ""
  var failed = false
  /** Addresses this frame checked with EXTCODESIZE and found empty, as a high-level call does before calling. */
  val checkedWithoutCode = LinkedHashSet<String>()

  /** This frame, then the last call it made while that call failed, and so on down to where the failure started. */
  fun failurePath(): List<Frame> {
    val path = arrayListOf(this)
    var frame = this
    while (true) {
      frame = frame.children.lastOrNull()?.takeIf { it.failed } ?: break
      path += frame
    }
    return path
  }
}

object FrameAnalysis {
  private val ENDS_NORMALLY = setOf("RETURN", "STOP", "SELFDESTRUCT")

  /** Splits the [steps] of a failed transaction into frames by call depth; [rootAddress] is null when it creates a contract. */
  fun build(steps: List<Step>, rootAddress: String?): Frame {
    val root = Frame(rootAddress?.let { AbiCodec.checksum(it) })
    val open = arrayListOf(root)
    var previous: Step? = null
    for (step in steps) {
      while (open.size > step.depth && open.size > 1) open.removeLast().finish()
      if (open.size < step.depth && previous != null) {
        // the previous instruction entered the code of another contract; CALL-like ops take the address second
        val address = if (previous.op in CALL_OPS) previous.stackTop.getOrNull(1)?.let(::address) else null
        val child = Frame(address)
        open.last().children += child
        open += child
      }
      if (previous != null && previous.op == "EXTCODESIZE" && previous.depth == step.depth && step.stackTop.firstOrNull()?.let(::isZero) == true) {
        previous.stackTop.firstOrNull()?.let(::address)?.let { open.last().checkedWithoutCode += it }
      }
      open.last().apply { pcs += step.pc; lastOp = step.op }
      previous = step
    }
    while (open.size > 1) open.removeLast().finish()
    root.failed = true
    return root
  }

  private fun Frame.finish() {
    failed = lastOp !in ENDS_NORMALLY
  }

  private fun isZero(word: String) = word.removePrefix("0x").all { it == '0' }

  private fun address(word: String): String? {
    val hex = word.removePrefix("0x").trimStart('0').padStart(40, '0')
    return if (hex.length > 40) null else AbiCodec.checksum("0x$hex")
  }
}
