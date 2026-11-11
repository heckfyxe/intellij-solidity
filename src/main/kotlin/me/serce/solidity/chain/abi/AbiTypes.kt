package me.serce.solidity.chain.abi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

sealed class AbiType {
  abstract val canonical: String
  open val isDynamic: Boolean get() = false

  data class UInt(val bits: kotlin.Int) : AbiType() { override val canonical get() = "uint$bits" }
  data class Int(val bits: kotlin.Int) : AbiType() { override val canonical get() = "int$bits" }
  object Address : AbiType() { override val canonical get() = "address"; override fun toString() = canonical }
  object Bool : AbiType() { override val canonical get() = "bool"; override fun toString() = canonical }
  data class FixedBytes(val size: kotlin.Int) : AbiType() { override val canonical get() = "bytes$size" }
  object Bytes : AbiType() {
    override val canonical get() = "bytes"; override val isDynamic get() = true; override fun toString() = canonical
  }
  object Str : AbiType() {
    override val canonical get() = "string"; override val isDynamic get() = true; override fun toString() = canonical
  }
  /** [length] is null for dynamic arrays `T[]`. */
  data class Array(val element: AbiType, val length: kotlin.Int?) : AbiType() {
    override val canonical get() = "${element.canonical}[${length ?: ""}]"
    override val isDynamic get() = length == null || element.isDynamic
  }
  /** A type we can't encode yet (e.g. `fixed128x18`); keeps the rest of the contract usable. */
  data class Unsupported(override val canonical: String) : AbiType()
  data class Tuple(val components: List<AbiParam>) : AbiType() {
    override val canonical get() = components.joinToString(",", "(", ")") { it.type.canonical }
    override val isDynamic get() = components.any { it.type.isDynamic }
  }

  /** Size of the head slot(s) this type takes when encoded inline. */
  val headSize: kotlin.Int
    get() = when {
      isDynamic -> 32
      this is Array -> length!! * element.headSize
      this is Tuple -> components.sumOf { it.type.headSize }
      else -> 32
    }

  companion object {
    private val ARRAY_SUFFIX = Regex("""^(.*)\[(\d*)]$""")

    /** Parses a type from an ABI json param (`type` + optional `components`). */
    fun parse(type: String, components: List<AbiParam> = emptyList()): AbiType {
      ARRAY_SUFFIX.find(type)?.let { m ->
        val (inner, len) = m.destructured
        return Array(parse(inner, components), len.toIntOrNull())
      }
      return when {
        type == "tuple" -> Tuple(components)
        type == "address" -> Address
        type == "bool" -> Bool
        type == "string" -> Str
        type == "bytes" -> Bytes
        type == "function" -> FixedBytes(24)
        type.startsWith("uint") -> type.removePrefix("uint").ifEmpty { "256" }.toIntOrNull()?.let { UInt(it) } ?: Unsupported(type)
        type.startsWith("int") -> type.removePrefix("int").ifEmpty { "256" }.toIntOrNull()?.let { Int(it) } ?: Unsupported(type)
        type.startsWith("bytes") -> type.removePrefix("bytes").toIntOrNull()?.let { FixedBytes(it) } ?: Unsupported(type)
        else -> Unsupported(type)
      }
    }
  }
}

data class AbiParam(val name: String, val type: AbiType, val internalType: String? = null, val indexed: Boolean = false) {
  /** e.g. `uint256 amount`, or `struct Order order` when internalType names a struct. */
  val displayType: String
    get() = internalType?.takeIf { type is AbiType.Tuple || (type is AbiType.Array && it.startsWith("struct")) }
      ?.removePrefix("struct ") ?: type.canonical
}

enum class Mutability { PURE, VIEW, NONPAYABLE, PAYABLE;
  val isReadOnly get() = this == PURE || this == VIEW
}

sealed class AbiEntry {
  abstract val inputs: List<AbiParam>

  data class Function(
    val name: String,
    override val inputs: List<AbiParam>,
    val outputs: List<AbiParam>,
    val mutability: Mutability,
  ) : AbiEntry() {
    val signature by lazy { signature(name, inputs) }
    val selector: ByteArray by lazy { Keccak.hash(signature).copyOf(4) }
  }

  data class Constructor(override val inputs: List<AbiParam>, val mutability: Mutability) : AbiEntry()

  data class Event(val name: String, override val inputs: List<AbiParam>, val anonymous: Boolean) : AbiEntry() {
    val signature by lazy { signature(name, inputs) }
    val topic: ByteArray by lazy { Keccak.hash(signature) }
  }

  data class Error(val name: String, override val inputs: List<AbiParam>) : AbiEntry() {
    val signature by lazy { signature(name, inputs) }
    val selector: ByteArray by lazy { Keccak.hash(signature).copyOf(4) }
  }

  companion object {
    /** Canonical signature, e.g. `transfer(address,uint256)`. */
    fun signature(name: String, inputs: List<AbiParam>) = "$name${inputs.joinToString(",", "(", ")") { it.type.canonical }}"
  }
}

class ContractAbi(val entries: List<AbiEntry>) {
  val functions = entries.filterIsInstance<AbiEntry.Function>()
  val events = entries.filterIsInstance<AbiEntry.Event>()
  val errors = entries.filterIsInstance<AbiEntry.Error>()
  val constructor = entries.filterIsInstance<AbiEntry.Constructor>().firstOrNull()

  companion object {
    private val mapper = ObjectMapper()

    fun parse(json: String): ContractAbi = parse(mapper.readTree(json))

    fun parse(node: JsonNode): ContractAbi = ContractAbi(node.mapNotNull { entry(it) })

    private fun entry(n: JsonNode): AbiEntry? {
      val inputs = params(n["inputs"])
      val mutability = mutability(n)
      return when (n["type"]?.asText() ?: "function") {
        "function" -> AbiEntry.Function(n["name"].asText(), inputs, params(n["outputs"]), mutability)
        "constructor" -> AbiEntry.Constructor(inputs, mutability)
        "event" -> AbiEntry.Event(n["name"].asText(), inputs, n["anonymous"]?.asBoolean() ?: false)
        "error" -> AbiEntry.Error(n["name"].asText(), inputs)
        else -> null
      }
    }

    private fun mutability(n: JsonNode): Mutability = when (n["stateMutability"]?.asText()) {
      "pure" -> Mutability.PURE
      "view" -> Mutability.VIEW
      "payable" -> Mutability.PAYABLE
      "nonpayable" -> Mutability.NONPAYABLE
      else -> when {
        n["constant"]?.asBoolean() == true -> Mutability.VIEW
        n["payable"]?.asBoolean() == true -> Mutability.PAYABLE
        else -> Mutability.NONPAYABLE
      }
    }

    private fun params(n: JsonNode?): List<AbiParam> = n?.map { p ->
      AbiParam(
        name = p["name"]?.asText().orEmpty(),
        type = AbiType.parse(p["type"].asText(), params(p["components"])),
        internalType = p["internalType"]?.asText(),
        indexed = p["indexed"]?.asBoolean() ?: false,
      )
    } ?: emptyList()
  }
}
