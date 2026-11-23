package me.serce.solidity.chain.abi

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Reads an ABI the user pasted or opened: a JSON ABI, a JSON file that holds one (Forge and Hardhat artifacts,
 * Sourcify and Etherscan responses), or human-readable signatures such as `function balanceOf(address) view returns (uint256)`,
 * one per line or `;`-separated, optionally wrapped in a Solidity `interface X { … }`.
 */
object AbiText {
  /** [json] is a normalized ABI array; [name] is the contract name when the text says it. */
  class Parsed(val json: String, val name: String?)

  private val mapper = ObjectMapper()

  /** @throws AbiInputException when [text] is none of the above, or describes nothing callable. */
  fun parse(text: String): Parsed {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw AbiInputException("The ABI is empty")
    val parsed = if (trimmed.startsWith("[") || trimmed.startsWith("{")) fromJson(trimmed) else HumanReadable(trimmed).parse()
    val abi = try {
      ContractAbi.parse(parsed.json)
    } catch (e: RuntimeException) {
      throw AbiInputException("Not a valid ABI: ${e.message}")
    }
    if (abi.entries.isEmpty()) throw AbiInputException("The ABI has no functions, events or errors")
    return parsed
  }

  /**
   * Names the structs of [abiJson] that have no `internalType` (ABIs written by hand or as signatures), so
   * `cast interface` declares them instead of printing tuples Solidity can't parse.
   */
  fun withStructNames(abiJson: String, interfaceName: String): String {
    val abi = mapper.readTree(abiJson) as? ArrayNode ?: return abiJson
    val used = HashSet<String>()
    fun unique(base: String): String {
      var name = base
      var i = 2
      while (!used.add(name)) name = "$base${i++}"
      return name
    }
    fun name(params: JsonNode?, owner: String, role: String) {
      params?.forEachIndexed { i, p ->
        val type = p["type"]?.asText() ?: return@forEachIndexed
        if (!type.startsWith("tuple")) return@forEachIndexed
        val paramName = p["name"]?.asText().orEmpty()
        val struct = unique(owner + (paramName.ifEmpty { "$role$i" }).replaceFirstChar { it.uppercase() })
        if (p["internalType"]?.asText().isNullOrEmpty()) (p as ObjectNode).put("internalType", "struct $interfaceName.$struct${type.removePrefix("tuple")}")
        name(p["components"], struct, "Field")
      }
    }
    for (entry in abi) {
      val owner = entry["name"]?.asText().orEmpty().replaceFirstChar { it.uppercase() }
      name(entry["inputs"], owner, "Arg")
      name(entry["outputs"], owner, "Result")
    }
    return mapper.writeValueAsString(abi)
  }

  private fun fromJson(text: String): Parsed {
    val node = try {
      mapper.readTree(text)
    } catch (e: JsonProcessingException) {
      throw AbiInputException("Not valid JSON: ${e.originalMessage}")
    }
    val (abi, name) = findAbi(node) ?: throw AbiInputException("No ABI in this JSON: expected an array, or an object with an \"abi\" field")
    abi.forEach { if (!it.isObject || (it["type"] == null && it["name"] == null)) throw AbiInputException("Not an ABI entry: $it") }
    return Parsed(mapper.writeValueAsString(abi), name)
  }

  /** The ABI array in [node] and the contract name next to it, if any. */
  private fun findAbi(node: JsonNode): Pair<ArrayNode, String?>? {
    if (node is ArrayNode) return node to null
    if (!node.isObject) return null
    val name = listOf("contractName", "ContractName", "name").firstNotNullOfOrNull { node[it]?.takeIf { n -> n.isTextual }?.asText() }
      ?: node["compilation"]?.get("name")?.asText()
    for (field in listOf("abi", "ABI")) {
      val v = node[field] ?: continue
      if (v is ArrayNode) return v to name
      if (v.isTextual) (runCatching { mapper.readTree(v.asText()) }.getOrNull() as? ArrayNode)?.let { return it to name }
    }
    // Etherscan: {"status":"1","result":[{"ABI":"[…]","ContractName":"…"}]}
    node["result"]?.let { r -> (if (r is ArrayNode) r.firstOrNull() else r)?.let { findAbi(it) }?.let { return it } }
    node["output"]?.get("abi")?.let { if (it is ArrayNode) return it to name } // solc metadata
    return null
  }

  /** A recursive-descent parser for human-readable ABI signatures (as ethers and viem accept them) and Solidity interfaces. */
  private class HumanReadable(text: String) {
    private val tokens = tokenize(text)
    private var pos = 0
    private val entries = mapper.createArrayNode()
    private var name: String? = null

    fun parse(): Parsed {
      while (pos < tokens.size) statement()
      if (entries.isEmpty) throw AbiInputException("No function, event or error signatures found")
      return Parsed(mapper.writeValueAsString(entries), name)
    }

    private fun peek(offset: Int = 0) = tokens.getOrNull(pos + offset)
    private fun next(): String = tokens.getOrNull(pos++) ?: throw AbiInputException("Unexpected end of the ABI")
    private fun expect(token: String) {
      val t = next()
      if (t != token) throw AbiInputException("Expected '$token' but found '$t'")
    }

    private fun statement() {
      when (val t = next()) {
        ";", "{", "}" -> {}
        "pragma" -> skipPast(";")
        "interface", "contract", "library" -> {
          name = name ?: next()
          while (peek() != null && peek() != "{") next() // `is A, B`
        }
        "abstract" -> {}
        "function" -> function()
        "event" -> event()
        "error" -> error()
        "constructor" -> constructor()
        "fallback", "receive" -> { if (peek() == "(") params(); modifiers() }
        "struct", "enum" -> throw AbiInputException(
          "Structs and enums can't be declared here: write a struct parameter as tuple(type a, type b) and an enum as uint8"
        )
        else -> throw AbiInputException("Expected function, event or error but found '$t'")
      }
    }

    private fun skipPast(token: String) {
      while (pos < tokens.size && next() != token) Unit
    }

    private fun function() {
      val fnName = next()
      val inputs = params()
      val (mutability, _) = modifiers()
      val outputs = if (peek() == "returns") { next(); params() } else mapper.createArrayNode()
      skipBody()
      entries.addObject().apply {
        put("type", "function"); put("name", fnName)
        set<JsonNode>("inputs", inputs); set<JsonNode>("outputs", outputs)
        put("stateMutability", mutability)
      }
    }

    private fun constructor() {
      val inputs = params()
      val (mutability, _) = modifiers()
      skipBody()
      entries.addObject().apply { put("type", "constructor"); set<JsonNode>("inputs", inputs); put("stateMutability", mutability) }
    }

    private fun event() {
      val evName = next()
      val inputs = params(allowIndexed = true)
      val anonymous = peek() == "anonymous"
      if (anonymous) next()
      entries.addObject().apply { put("type", "event"); put("name", evName); set<JsonNode>("inputs", inputs); put("anonymous", anonymous) }
    }

    private fun error() {
      val errName = next()
      val inputs = params()
      entries.addObject().apply { put("type", "error"); put("name", errName); set<JsonNode>("inputs", inputs) }
    }

    /** Skips a function body when a whole contract was pasted. */
    private fun skipBody() {
      if (peek() != "{") return
      var depth = 0
      do {
        when (next()) { "{" -> depth++; "}" -> depth-- }
      } while (depth > 0 && pos < tokens.size)
    }

    /** Visibility and mutability words; returns the state mutability. */
    private fun modifiers(): Pair<String, Boolean> {
      var mutability = "nonpayable"
      while (true) {
        when (peek()) {
          "external", "public", "internal", "private", "virtual" -> next()
          "view", "pure", "payable", "nonpayable" -> mutability = next()
          "constant" -> { next(); mutability = "view" }
          "override" -> { next(); if (peek() == "(") skipParens() }
          else -> return mutability to true
        }
      }
    }

    private fun skipParens() {
      var depth = 0
      do {
        when (next()) { "(" -> depth++; ")" -> depth-- }
      } while (depth > 0)
    }

    private fun params(allowIndexed: Boolean = false): ArrayNode {
      val list = mapper.createArrayNode()
      expect("(")
      if (peek() == ")") { next(); return list }
      while (true) {
        list.add(param(allowIndexed))
        when (val t = next()) {
          ")" -> return list
          "," -> continue
          else -> throw AbiInputException("Expected ',' or ')' but found '$t'")
        }
      }
    }

    private fun param(allowIndexed: Boolean): ObjectNode {
      // name and type first, as solc writes them
      val node = mapper.createObjectNode().put("name", "").put("type", "")
      var type: String
      if (peek() == "tuple" || peek() == "(") {
        if (peek() == "tuple") next()
        val components = params()
        type = "tuple"
        node.set<JsonNode>("components", components)
      } else {
        type = elementaryType(next())
      }
      while (peek() == "[") {
        next()
        val size = if (peek() != "]") next().also { if (it.toIntOrNull() == null) throw AbiInputException("Bad array size '$it'") } else ""
        expect("]")
        type += "[$size]"
      }
      var paramName = ""
      while (true) {
        val t = peek() ?: break
        if (t == "," || t == ")") break
        next()
        when (t) {
          "indexed" -> if (allowIndexed) node.put("indexed", true) else throw AbiInputException("'indexed' is only allowed in events")
          "memory", "calldata", "storage", "payable" -> {}
          else -> paramName = t
        }
      }
      if (allowIndexed && !node.has("indexed")) node.put("indexed", false)
      return node.put("name", paramName).put("type", type)
    }

    private fun elementaryType(t: String): String {
      val normalized = when (t) {
        "uint" -> "uint256"
        "int" -> "int256"
        "byte" -> "bytes1"
        else -> t
      }
      if (AbiType.parse(normalized) !is AbiType.Unsupported) return normalized
      throw AbiInputException(
        "Unknown type '$t': write address for contracts and interfaces, uint8 for enums and tuple(type a, type b) for structs"
      )
    }

    companion object {
      private val TOKEN = Regex("""[A-Za-z_$][A-Za-z0-9_$.]*|\d+|[()\[\],;{}]|"(?:[^"\\]|\\.)*"|\S""")

      fun tokenize(text: String): List<String> {
        val noComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
        // every signature starts with a keyword, so one per line works without semicolons
        return TOKEN.findAll(noComments).map { it.value }
          .filter { !it.startsWith("\"") }.toList()
      }
    }
  }
}
