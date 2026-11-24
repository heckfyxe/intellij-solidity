package me.serce.solidity.chain.foundry

import me.serce.solidity.util.text
import me.serce.solidity.util.Json
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import me.serce.solidity.chain.debug.CompiledSources
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What forge compiled into [outDir], read once per build and shared by Deploy, Attach, revert decoding and failure
 * tracing. Only the fields used here are parsed: the AST and most of the metadata, the bulk of each file, are skipped.
 */
class ForgeOutput private constructor(private val root: Path, private val outDir: Path) {
  /** Each artifact with only `abi`, `bytecode`, `deployedBytecode`, `id` and `metadata.settings.compilationTarget`. */
  val artifactJsons: List<JsonNode> by lazy { artifactFiles(outDir).mapNotNull { readPruned(it) } }

  val artifacts: List<Artifact> by lazy { artifactJsons.mapNotNull { toArtifact(it) }.sortedBy { it.name } }

  /** Source maps of the compiled contracts, to name the line a failure happened at. */
  val sources: CompiledSources by lazy { CompiledSources(root, outDir, artifactJsons) }

  companion object {
    private val cache = ConcurrentHashMap<Pair<Path, Path>, Pair<List<Long>, SoftReference<ForgeOutput>>>()

    /** The fields read from an artifact: null takes the whole value, a map only the listed fields of an object. */
    private val KEEP: Map<String, Any?> = mapOf(
      "abi" to null, "bytecode" to null, "deployedBytecode" to null, "id" to null,
      "metadata" to mapOf("settings" to mapOf("compilationTarget" to null)),
    )

    /** The output in [outDir], parsed again only after forge wrote, added or removed an artifact. */
    fun of(root: Path, outDir: Path): ForgeOutput {
      val key = root to outDir
      val stamp = stamp(outDir)
      cache[key]?.let { (s, ref) -> if (s == stamp) ref.get()?.let { return it } }
      return ForgeOutput(root, outDir).also { cache[key] = stamp to SoftReference(it) }
    }

    private fun stamp(outDir: Path): List<Long> {
      val files = artifactFiles(outDir)
      return listOf(files.size.toLong(), files.maxOfOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) } ?: 0L)
    }

    private fun toArtifact(json: JsonNode): Artifact? {
      val abi = json["abi"] ?: return null
      val target = json.compilationTarget() ?: return null
      val bytecode = json["bytecode"]
      return Artifact(
        name = target.value.asText(),
        sourcePath = target.key,
        abiJson = Json.mapper.writeValueAsString(abi),
        bytecode = bytecode?.text("object") ?: "0x",
        hasLinkReferences = (bytecode?.get("linkReferences")?.size() ?: 0) > 0,
      )
    }

    private fun readPruned(file: Path): JsonNode? = try {
      Json.mapper.factory.createParser(file.toFile()).use { p -> if (p.nextToken() == JsonToken.START_OBJECT) readObject(p, KEEP) else null }
    } catch (e: Exception) {
      null
    }

    /** With [p] on an object's start: the fields in [keep], skipping the others without building them. */
    @Suppress("UNCHECKED_CAST")
    private fun readObject(p: JsonParser, keep: Map<String, Any?>): ObjectNode {
      val node = Json.mapper.createObjectNode()
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        val name = p.currentName()
        val token = p.nextToken()
        val nested = keep[name]
        when {
          name !in keep -> p.skipChildren()
          nested == null -> node.set<JsonNode>(name, Json.mapper.readTree<JsonNode>(p) ?: NullNode.instance)
          token == JsonToken.START_OBJECT -> node.set<JsonNode>(name, readObject(p, nested as Map<String, Any?>))
          else -> p.skipChildren()
        }
      }
      return node
    }
  }
}
