package me.serce.solidity.chain.debug

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.serce.solidity.chain.LinePositions
import me.serce.solidity.chain.Offsets
import me.serce.solidity.chain.abi.hexToBytes
import me.serce.solidity.chain.foundry.artifactFiles
import me.serce.solidity.chain.foundry.compilationTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** A place in a source file, as shown next to a failure. Paths are relative to the project root. */
data class SourceLocation(val path: String, val line: Int, val column: Int, val code: String)

/** EVM code compiled from one contract, with what's needed to map a program counter back to its source. */
class CompiledCode(
  val contract: String,
  private val bytes: ByteArray,
  /** Bytes that differ on chain: immutables and linked library addresses. */
  private val wildcards: List<IntRange>,
  private val sourceMap: String,
  private val sourcePaths: Map<Int, String>,
  private val readSource: (String) -> String?,
) {
  private val masked: BooleanArray by lazy {
    BooleanArray(bytes.size).also { mask -> wildcards.forEach { r -> r.forEach { if (it in mask.indices) mask[it] = true } } }
  }

  /** Deployed code of this contract, or creation code it starts with (followed by constructor arguments). */
  fun matches(code: ByteArray, prefix: Boolean): Boolean {
    if (bytes.isEmpty()) return false
    if (if (prefix) code.size < bytes.size else code.size != bytes.size) return false
    if (wildcards.isEmpty()) return bytes.indices.all { bytes[it] == code[it] }
    return bytes.indices.all { masked[it] || bytes[it] == code[it] }
  }

  private val instructionIndex: Map<Int, Int> by lazy { instructionIndex(bytes) }
  private val entries: List<SourceMapEntry> by lazy { SourceMapEntry.parse(sourceMap) }

  /**
   * The source of the last of [pcs] that comes from a real file. Compiler-generated helpers (ABI decoding, checked
   * arithmetic, revert forwarding) have no file of their own, so walking back lands on the expression that used them.
   */
  fun locate(pcs: List<Int>): SourceLocation? {
    for (pc in pcs.asReversed()) {
      val entry = instructionIndex[pc]?.let { entries.getOrNull(it) } ?: continue
      if (entry.length <= 0) continue
      val path = sourcePaths[entry.file] ?: continue
      val text = readSource(path) ?: continue
      val start = Offsets.byteToChar(text, entry.start)
      val end = Offsets.byteToChar(text, entry.start + entry.length)
      val (line, column) = LinePositions(text).lineColAt(start)
      return SourceLocation(path, line, column, snippet(text, start, end))
    }
    return null
  }

  companion object {
    /** Byte offset of each instruction → its index, which is what source maps count in. */
    fun instructionIndex(code: ByteArray): Map<Int, Int> {
      val result = HashMap<Int, Int>()
      var pc = 0
      var index = 0
      while (pc < code.size) {
        result[pc] = index++
        val op = code[pc].toInt() and 0xff
        pc += 1 + if (op in 0x60..0x7f) op - 0x5f else 0
      }
      return result
    }

    /** The first line of the code at a location, whitespace collapsed and shortened, for display. */
    fun snippet(text: String, start: Int, end: Int): String {
      val from = start.coerceIn(0, text.length)
      val raw = text.substring(from, end.coerceIn(from, text.length))
      val firstLine = raw.lineSequence().first().trim()
      val oneLine = if (raw.contains('\n')) "$firstLine …" else firstLine
      val collapsed = oneLine.replace(WHITESPACE, " ")
      return if (collapsed.length > 100) collapsed.take(99) + "…" else collapsed
    }

    private val WHITESPACE = Regex("\\s+")
  }
}

/** One decompressed `s:l:f:j:m` entry of a solc source map. */
data class SourceMapEntry(val start: Int, val length: Int, val file: Int) {
  companion object {
    fun parse(map: String): List<SourceMapEntry> {
      if (map.isEmpty()) return emptyList()
      val result = ArrayList<SourceMapEntry>()
      var s = 0
      var l = 0
      var f = -1
      for (item in map.split(';')) {
        val parts = item.split(':')
        // an empty field repeats the previous entry's value
        parts.getOrNull(0)?.toIntOrNull()?.let { s = it }
        parts.getOrNull(1)?.toIntOrNull()?.let { l = it }
        parts.getOrNull(2)?.toIntOrNull()?.let { f = it }
        result += SourceMapEntry(s, l, f)
      }
      return result
    }
  }
}

/** Contracts compiled by forge into [outDir], read lazily and only when a failure needs explaining. */
class CompiledSources(private val root: Path, private val outDir: Path) {
  private val sourceCache = HashMap<String, String?>()
  private val readSource: (String) -> String? = { path ->
    sourceCache.getOrPut(path) { root.resolve(path).takeIf { it.isRegularFile() }?.let { runCatching { it.readText() }.getOrNull() } }
  }

  private val buildInfos: List<Map<Int, String>> by lazy {
    val dir = outDir.resolve("build-info")
    if (!dir.isDirectory()) emptyList() else Files.list(dir).use { files ->
      files.filter { it.extension == "json" }.toList()
        .sortedByDescending { Files.getLastModifiedTime(it) }
        .mapNotNull { readSourceIds(it) }
    }
  }

  private val contracts: List<Pair<CompiledCode?, CompiledCode?>> by lazy { artifactFiles(outDir).mapNotNull { read(it) } }

  fun runtime(code: ByteArray): CompiledCode? =
    if (code.isEmpty()) null else contracts.firstNotNullOfOrNull { (_, runtime) -> runtime?.takeIf { it.matches(code, prefix = false) } }

  fun creation(initCode: ByteArray): CompiledCode? =
    if (initCode.isEmpty()) null else contracts.firstNotNullOfOrNull { (creation, _) -> creation?.takeIf { it.matches(initCode, prefix = true) } }

  private fun read(file: Path): Pair<CompiledCode?, CompiledCode?>? = try {
    val json = mapper.readTree(file.toFile())
    val target = json.compilationTarget()
    val sourceId = json["id"]?.asInt()
    if (target == null || sourceId == null) null else {
      val sourcePath = target.key
      val name = target.value.asText()
      val paths = buildInfos.firstOrNull { it[sourceId] == sourcePath } ?: mapOf(sourceId to sourcePath)
      code(name, json["bytecode"], paths) to code(name, json["deployedBytecode"], paths)
    }
  } catch (e: Exception) {
    null
  }

  private fun code(name: String, node: JsonNode?, paths: Map<Int, String>): CompiledCode? {
    val hex = node?.get("object")?.asText()?.removePrefix("0x")?.takeIf { it.isNotEmpty() } ?: return null
    // unlinked library placeholders are 40 hex chars like `__$…$__`; they become wildcards via linkReferences
    val bytes = runCatching { hex.replace(PLACEHOLDER, "0".repeat(40)).hexToBytes() }.getOrNull() ?: return null
    val wildcards = ArrayList<IntRange>()
    node["linkReferences"]?.forEach { file -> file.forEach { lib -> lib.forEach { wildcards += it.range() } } }
    node["immutableReferences"]?.forEach { refs -> refs.forEach { wildcards += it.range() } }
    return CompiledCode(name, bytes, wildcards, node["sourceMap"]?.asText().orEmpty(), paths, readSource)
  }

  private fun JsonNode.range(): IntRange {
    val start = this["start"].asInt()
    return start until start + this["length"].asInt()
  }

  /** Only `source_id_to_path`: older forge versions put the whole compiler input and output in the same file. */
  private fun readSourceIds(file: Path): Map<Int, String>? = try {
    mapper.factory.createParser(file.toFile()).use { p ->
      if (p.nextToken() != JsonToken.START_OBJECT) return null
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        val field = p.currentName()
        p.nextToken()
        if (field == "source_id_to_path") {
          val node: JsonNode = mapper.readTree(p)
          return node.fields().asSequence().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.asText() } }.toMap()
        }
        p.skipChildren()
      }
      null
    }
  } catch (e: Exception) {
    null
  }

  companion object {
    private val mapper = ObjectMapper()
    private val PLACEHOLDER = Regex("__[$][0-9a-fA-F]{34}[$]__")
  }
}
