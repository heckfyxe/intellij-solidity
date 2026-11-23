package me.serce.solidity.chain.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.abi.AbiInputException
import me.serce.solidity.chain.abi.AbiType
import me.serce.solidity.chain.abi.AbiValueParser
import me.serce.solidity.chain.abi.Keccak
import me.serce.solidity.chain.abi.toHex
import me.serce.solidity.chain.abi.toWord
import java.math.BigInteger

/** Reads one 32-byte storage word of the contract being inspected. */
fun interface StorageReader {
  fun word(slot: BigInteger): ByteArray
}

/**
 * A contract's storage layout as solc reports it (`forge inspect C storageLayout --json`), and how to read its
 * variables from raw slots: packed value types, short and long strings, fixed and dynamic arrays (elements packed like
 * solc packs them), structs, and entries reached by an access path such as `[0xBEEF][2].amount`.
 */
class StorageLayout(val variables: List<Variable>, private val types: Map<String, Type>) {

  class Type(
    val id: String,
    val label: String,
    val encoding: String,
    val size: Int,
    val key: String?,
    val value: String?,
    val base: String?,
    val members: List<Variable>,
  )

  /** A state variable or struct member; [slot] is absolute for state variables and relative for members. */
  class Variable(val label: String, val slot: BigInteger, val offset: Int, val type: String)

  /** One line of the layout view: struct members are flattened as `p.a`, with absolute slots. */
  class Row(val name: String, val slot: BigInteger, val offset: Int, val type: Type)

  /** Where a value lives: its slot, its byte offset from the slot's low-order end, and its type. */
  class Location(val slot: BigInteger, val offset: Int, val type: Type)

  /** One step of an access path: `[key]` for a mapping key or array index, `.name` for a struct member. */
  sealed class Step {
    class Key(val text: String) : Step() { override fun toString() = "[$text]" }
    class Member(val name: String) : Step() { override fun toString() = ".$name" }
  }

  fun type(id: String): Type = types[id] ?: Type(id, id, "inplace", 32, null, null, null, emptyList())

  fun rows(): List<Row> = buildList { variables.forEach { addRows(it.label, it.slot, it, this) } }

  private fun addRows(name: String, slot: BigInteger, v: Variable, out: MutableList<Row>) {
    val t = type(v.type)
    if (t.members.isNotEmpty() && t.encoding == "inplace") {
      t.members.forEach { m -> addRows("$name.${m.label}", slot + m.slot, m, out) }
    } else {
      out += Row(name, slot, v.offset, t)
    }
  }

  /** What [row] holds, read through [reader]. */
  fun read(row: Row, reader: StorageReader): String = read(Location(row.slot, row.offset, row.type), reader)

  /**
   * What [loc] holds: a value, a string, an array with its first elements when they are value types, a struct's
   * members, or a hint for mappings. [depth] stops structs nested in structs from reading without bound.
   */
  fun read(loc: Location, reader: StorageReader, depth: Int = 0): String {
    val t = loc.type
    val words = cached(reader)
    return when {
      t.encoding == "mapping" -> "mapping: look up a key"
      t.encoding == "dynamic_array" -> {
        val length = BigInteger(1, words.word(loc.slot))
        val start = elementsSlot(loc.slot)
        val base = type(t.base.orEmpty())
        when {
          length.signum() == 0 -> "length 0"
          isValue(base) -> "length $length: " + preview(start, length, base, words)
          else -> "length $length, elements from slot ${start.slotLabel()}"
        }
      }
      t.encoding == "bytes" -> readBytes(loc.slot, t, words)
      t.base != null -> {
        val base = type(t.base)
        val length = staticLength(t)
        if (isValue(base) && length != null) preview(loc.slot, length, base, words) else "${(t.size + 31) / 32} slots from ${loc.slot.slotLabel()}"
      }
      t.members.isNotEmpty() -> if (depth >= 2) "struct at slot ${loc.slot.slotLabel()}" else t.members.joinToString(", ", "{", "}") { m ->
        "${m.label}: " + read(Location(loc.slot + m.slot, m.offset, type(m.type)), words, depth + 1)
      }
      t.size <= 32 -> decodeValue(extract(words.word(loc.slot), loc.offset, t.size), t.label)
      else -> "${(t.size + 31) / 32} slots from ${loc.slot.slotLabel()}"
    }
  }

  /** `[a, b, …]`: the first [PREVIEW] elements of an array of value types starting at [start]. */
  private fun preview(start: BigInteger, length: BigInteger, base: Type, words: StorageReader): String {
    val shown = length.min(BigInteger.valueOf(PREVIEW.toLong())).toInt()
    val values = (0 until shown).map { read(element(start, it.toBigInteger(), base), words) }
    return values.joinToString(", ", "[", if (length > shown.toBigInteger()) ", …]" else "]")
  }

  /**
   * Element [index] of an array whose elements start at [start]: elements of 16 bytes or less are packed several to a
   * slot from its low-order end, bigger ones (structs, fixed arrays) take whole slots each.
   */
  private fun element(start: BigInteger, index: BigInteger, base: Type): Location =
    if (base.size <= 32) {
      val perSlot = (32 / base.size).toBigInteger()
      Location(start + index / perSlot, (index % perSlot).toInt() * base.size, base)
    } else {
      Location(start + index * ((base.size + 31) / 32).toBigInteger(), 0, base)
    }

  private fun isValue(t: Type) = t.encoding == "inplace" && t.base == null && t.members.isEmpty() && t.size <= 32

  /** The length of a fixed array from its label: `uint256[3]`, `bool[2][]` is dynamic (its last brackets are empty). */
  private fun staticLength(t: Type): BigInteger? = STATIC_LENGTH.find(t.label)?.groupValues?.get(1)?.toBigInteger()

  /** Follows [path] (see [parsePath]) from [row]: mapping keys, array indexes with a bounds check, struct members. */
  fun access(row: Row, path: String, reader: StorageReader): Location =
    parsePath(path).fold(Location(row.slot, row.offset, row.type)) { loc, step ->
      when (step) {
        is Step.Key -> index(loc, step.text, reader)
        is Step.Member -> {
          if (loc.type.members.isEmpty()) throw AbiInputException("${loc.type.label} is not a struct")
          val m = loc.type.members.firstOrNull { it.label == step.name } ?: throw AbiInputException("${loc.type.label} has no member ${step.name}")
          Location(loc.slot + m.slot, m.offset, type(m.type))
        }
      }
    }

  private fun index(loc: Location, text: String, reader: StorageReader): Location {
    val t = loc.type
    return when {
      t.encoding == "mapping" -> Location(keySlot(loc.slot, t, text), 0, type(t.value.orEmpty()))
      t.encoding == "dynamic_array" -> {
        val length = BigInteger(1, reader.word(loc.slot))
        element(elementsSlot(loc.slot), checkedIndex(text, length), type(t.base.orEmpty()))
      }
      t.base != null -> element(loc.slot, checkedIndex(text, staticLength(t) ?: BigInteger.ZERO), type(t.base))
      else -> throw AbiInputException("${t.label} is not a mapping or an array")
    }
  }

  private fun checkedIndex(text: String, length: BigInteger): BigInteger {
    val i = try {
      parseSlot(text)
    } catch (e: AbiInputException) {
      throw AbiInputException("Enter an index: a number from 0")
    }
    if (i >= length) throw AbiInputException("Index $i is out of bounds: the length is $length")
    return i
  }

  /** The slot of `mapping[key]` for the mapping at [row], or of the key's struct/array start. */
  fun mappingSlot(row: Row, keyText: String): BigInteger = keySlot(row.slot, row.type, keyText)

  private fun keySlot(slot: BigInteger, mapping: Type, keyText: String): BigInteger {
    val keyType = type(mapping.key ?: throw AbiInputException("Not a mapping"))
    val abi = abiType(keyType.label)
    val key = AbiValueParser.parse(abi, keyText)
    // string and bytes keys are hashed as they are; value types padded to a word, bytesN left-aligned
    val keyBytes = when (abi) {
      AbiType.Str -> (key as String).toByteArray(Charsets.UTF_8)
      AbiType.Bytes -> key as ByteArray
      is AbiType.FixedBytes -> (key as ByteArray).copyOf(32)
      else -> AbiCodec.encode(listOf(abi), listOf(key))
    }
    return BigInteger(1, Keccak.hash(keyBytes + slot.toWord()))
  }

  /** The value stored at `mapping[key]`, decoded by the mapping's value type (nested mappings give their slot). */
  fun readMapping(row: Row, keyText: String, reader: StorageReader): String {
    val slot = mappingSlot(row, keyText)
    val valueType = type(row.type.value ?: return slot.toHex32())
    return if (valueType.encoding == "mapping") "mapping at slot ${slot.slotLabel()}" else read(Location(slot, 0, valueType), reader)
  }

  private fun readBytes(slot: BigInteger, t: Type, reader: StorageReader): String {
    val word = reader.word(slot)
    val isString = t.label.startsWith("string")
    if (word[31].toInt() and 1 == 0) { // short: data in place, length * 2 in the lowest byte
      val length = (word[31].toInt() and 0xff) / 2
      return show(word.copyOf(length), isString)
    }
    val length = (BigInteger(1, word) - BigInteger.ONE).shiftRight(1).toInt()
    val shown = minOf(length, MAX_BYTES)
    val start = elementsSlot(slot)
    val data = (0 until (shown + 31) / 32).fold(ByteArray(0)) { acc, i -> acc + reader.word(start + BigInteger.valueOf(i.toLong())) }.copyOf(shown)
    return show(data, isString) + if (length > shown) "… ($length bytes)" else ""
  }

  private fun show(data: ByteArray, isString: Boolean) =
    if (isString) "\"" + String(data, Charsets.UTF_8).replace("\"", "\\\"") + "\"" else data.toHex()

  companion object {
    private const val MAX_BYTES = 256
    private const val PREVIEW = 10
    private val STATIC_LENGTH = Regex("\\[(\\d+)]$")

    /** A reader that asks [reader] for each slot once: packed array elements share slots. */
    private fun cached(reader: StorageReader): StorageReader =
      if (reader is CachedReader) reader else CachedReader(reader)

    private class CachedReader(private val reader: StorageReader) : StorageReader {
      private val words = HashMap<BigInteger, ByteArray>()
      override fun word(slot: BigInteger): ByteArray = words.getOrPut(slot) { reader.word(slot) }
    }

    /**
     * Parses an access path: `[key]` steps for mapping keys and array indexes, `.name` steps for struct members, e.g.
     * `[0xBEEF][2].amount`. Text that doesn't start with `[` or `.` is one key. A key may be a quoted string holding `]`.
     */
    fun parsePath(text: String): List<Step> {
      val t = text.trim()
      if (t.isEmpty()) throw AbiInputException("Enter a key or index")
      if (t[0] != '[' && t[0] != '.') return listOf(Step.Key(t))
      val steps = ArrayList<Step>()
      var i = 0
      while (i < t.length) {
        when (t[i]) {
          '[' -> {
            var j = i + 1
            var quote: Char? = null
            while (j < t.length && (quote != null || t[j] != ']')) {
              if (quote == null && (t[j] == '"' || t[j] == '\'')) quote = t[j] else if (t[j] == quote) quote = null
              j++
            }
            if (j >= t.length) throw AbiInputException("Missing ] in $t")
            steps += Step.Key(t.substring(i + 1, j).trim())
            i = j + 1
          }
          '.' -> {
            var j = i + 1
            while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$')) j++
            if (j == i + 1) throw AbiInputException("Expected a member name after . in $t")
            steps += Step.Member(t.substring(i + 1, j))
            i = j
          }
          else -> if (t[i].isWhitespace()) i++ else throw AbiInputException("Expected [ or . at \"${t.substring(i)}\"")
        }
      }
      return steps
    }
    private val mapper = ObjectMapper()

    fun parse(json: String): StorageLayout {
      val node = mapper.readTree(json)
      val types = node["types"]?.fields()?.asSequence()?.associate { (id, t) ->
        id to Type(
          id = id,
          label = t["label"]?.asText() ?: id,
          encoding = t["encoding"]?.asText() ?: "inplace",
          size = t["numberOfBytes"]?.asText()?.toIntOrNull() ?: 32,
          key = t["key"]?.asText(),
          value = t["value"]?.asText(),
          base = t["base"]?.asText(),
          members = variables(t["members"]),
        )
      }.orEmpty()
      return StorageLayout(variables(node["storage"]), types)
    }

    private fun variables(n: JsonNode?): List<Variable> = n?.map {
      Variable(it["label"].asText(), BigInteger(it["slot"].asText()), it["offset"]?.asInt() ?: 0, it["type"].asText())
    }.orEmpty()

    /** Where a dynamic array's elements and a long string's data start: `keccak256(slot)`. */
    fun elementsSlot(slot: BigInteger): BigInteger = BigInteger(1, Keccak.hash(slot.toWord()))

    /** The [size] bytes at [offset] counted from the word's low-order (right) end, where solc packs variables. */
    fun extract(word: ByteArray, offset: Int, size: Int): ByteArray = word.copyOfRange(32 - offset - size, 32 - offset)

    /** Decodes a packed value type by its solc label: `uint8`, `int24`, `bool`, `address`, `contract C`, `enum E`, `bytes4`. */
    fun decodeValue(bytes: ByteArray, label: String): String {
      val unsigned = BigInteger(1, bytes)
      return when {
        label == "bool" -> (unsigned.signum() != 0).toString()
        label == "address" || label == "address payable" || label.startsWith("contract ") || label.startsWith("interface ") ->
          AbiCodec.checksum(bytes.copyOfRange(maxOf(0, bytes.size - 20), bytes.size).toHex())
        label.startsWith("int") -> BigInteger(bytes).toString() // two's complement of the packed width
        label.startsWith("bytes") -> bytes.toHex()
        label.startsWith("enum ") -> "$unsigned"
        else -> unsigned.toString()
      }
    }

    /**
     * Decodes value types the user lists for [slot] (e.g. `uint128, address, bool`), packed as solc packs state
     * variables: from the word's low-order end, a value that doesn't fit what is left moving to the next slot.
     * Returns one `slot +offset type: value` line per type.
     */
    fun decodePacked(typesText: String, slot: BigInteger, reader: StorageReader): List<String> {
      val types = typesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      if (types.isEmpty()) throw AbiInputException("Enter types, e.g. uint128, address, bool")
      val sized = types.map { name ->
        val label = name.replace(Regex("\\s+"), " ")
        val size = when (val t = AbiType.parse(label.removePrefix("address payable").ifEmpty { "address" })) {
          is AbiType.UInt -> t.bits / 8
          is AbiType.Int -> t.bits / 8
          AbiType.Address -> 20
          AbiType.Bool -> 1
          is AbiType.FixedBytes -> t.size
          else -> if (label.startsWith("enum")) 1 else throw AbiInputException("$name is not a value type that fits in a slot")
        }
        if (size !in 1..32) throw AbiInputException("$name is not a valid type")
        label to size
      }
      val words = HashMap<BigInteger, ByteArray>()
      var current = slot
      var offset = 0
      return sized.map { (label, size) ->
        if (offset + size > 32) { current += BigInteger.ONE; offset = 0 }
        val word = words.getOrPut(current) { reader.word(current) }
        val value = decodeValue(extract(word, offset, size), if (label.startsWith("enum")) "enum" else label)
        "slot ${current.slotLabel()}${if (offset > 0) " +$offset" else ""}  $label: $value".also { offset += size }
      }
    }

    /** Parses a slot typed by the user: decimal or 0x-hex. */
    fun parseSlot(text: String): BigInteger {
      val t = text.trim().replace("_", "")
      val n = try {
        if (t.startsWith("0x", ignoreCase = true)) BigInteger(t.substring(2), 16) else BigInteger(t)
      } catch (e: NumberFormatException) {
        throw AbiInputException("Enter a slot number, decimal or 0x hex")
      }
      if (n.signum() < 0 || n.bitLength() > 256) throw AbiInputException("A slot is between 0 and 2²⁵⁶ - 1")
      return n
    }

    private fun abiType(label: String): AbiType = when {
      label.startsWith("contract ") || label.startsWith("interface ") || label == "address payable" -> AbiType.Address
      label.startsWith("enum ") -> AbiType.UInt(8)
      else -> AbiType.parse(label).takeIf { it !is AbiType.Unsupported } ?: throw AbiInputException("Can't encode a $label key")
    }
  }
}

fun BigInteger.toHex32(): String = toWord().toHex()

/** Small slots in decimal, as layouts list them; hashed ones in hex. */
fun BigInteger.slotLabel(): String = if (bitLength() <= 32) toString() else toHex32()
