package me.serce.solidity.chain.foundry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import me.serce.solidity.chain.abi.ContractAbi
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.SoliditySettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

class FoundryException(message: String) : Exception(message)

/** A compiled contract read from forge's `out/` directory. */
class Artifact(
  val name: String,
  /** Source path relative to the Foundry root, e.g. `src/FundMe.sol`. */
  val sourcePath: String,
  val abiJson: String,
  val bytecode: String,
  val hasLinkReferences: Boolean,
) {
  val abi: ContractAbi by lazy { ContractAbi.parse(abiJson) }
  val isDeployable get() = bytecode.length > 2 && !hasLinkReferences
  override fun toString() = name
}

data class ForgeConfig(
  val src: String,
  val out: String,
  val libs: List<String>,
  val remappings: List<String>,
  /** Explicit solc version or path from foundry.toml, if any. */
  val solc: String?,
)

data class CompilerMessage(
  val file: String?,
  val start: Int,
  val end: Int,
  val severity: String,
  val type: String,
  val message: String,
  val formatted: String,
)

class BuildResult(val success: Boolean, val messages: List<CompilerMessage>)

/** A Foundry project: the directory containing foundry.toml. */
class FoundryProject(val root: Path, private val forge: String) {
  /** [file]'s path relative to [root], or null when it is outside. */
  fun relativePath(file: VirtualFile): String? =
    runCatching { root.relativize(Path.of(file.path)) }.getOrNull()?.takeIf { !it.startsWith("..") }?.toString()?.replace('\\', '/')

  /** Cached until foundry.toml or remappings.txt change; a failure is cached too, so the annotator doesn't respawn forge. */
  fun config(): ForgeConfig {
    val stamp = stamp()
    configCache[root]?.let { (s, c) -> if (s == stamp) return c.getOrThrow() }
    val result = runCatching { readConfig() }
    configCache[root] = stamp to result
    return result.getOrThrow()
  }

  /** The config (or its failure) if it is already known and up to date; never runs forge, so it is safe under a read action. */
  fun cachedConfig(): Result<ForgeConfig>? = configCache[root]?.takeIf { it.first == stamp() }?.second

  /** Reads the config on a pooled thread, at most once at a time per project, then calls [onLoaded] if it worked. */
  fun loadConfigInBackground(onLoaded: () -> Unit) {
    if (!loading.add(root)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        if (runCatching { config() }.isSuccess) onLoaded()
      } finally {
        loading.remove(root)
      }
    }
  }

  private fun stamp() = listOf("foundry.toml", "remappings.txt").map { root.resolve(it).toFile().lastModified() }

  private fun readConfig(): ForgeConfig {
    val out = exec(listOf(forge, "config", "--json"), timeoutMs = 30_000).let { o ->
      val text = o.stdout + o.stderr
      if (o.exitCode != 0) throw FoundryException("forge config failed: $text")
      text
    }
    val json = mapper.readTree(out.substring(out.indexOf('{')))
    return ForgeConfig(
      src = json["src"]?.asText() ?: "src",
      out = json["out"]?.asText() ?: "out",
      libs = json["libs"]?.map { it.asText() } ?: listOf("lib"),
      remappings = json["remappings"]?.map { it.asText() } ?: emptyList(),
      solc = (json["solc"]?.takeIf { !it.isNull } ?: json["solc_version"]?.takeIf { !it.isNull })?.asText(),
    )
  }

  fun build(): BuildResult {
    val output = exec(listOf(forge, "build", "--json"), timeoutMs = 600_000)
    val code = output.exitCode
    val out = output.stdout + output.stderr
    val jsonStart = out.indexOf("{\n").takeIf { it >= 0 } ?: out.indexOf('{')
    val json = if (jsonStart >= 0) runCatching { mapper.readTree(out.substring(jsonStart)) }.getOrNull() else null
    val messages = json?.get("errors")?.map { it.toCompilerMessage() } ?: emptyList()
    val success = code == 0 && messages.none { it.severity == "error" }
    if (!success && messages.isEmpty()) {
      return BuildResult(false, listOf(CompilerMessage(null, 0, 0, "error", "Error", out.trim().ifEmpty { "forge build failed" }, out)))
    }
    return BuildResult(success, messages)
  }

  fun artifacts(): List<Artifact> {
    val outDir = root.resolve(config().out)
    if (!outDir.isDirectory()) return emptyList()
    val result = ArrayList<Artifact>()
    Files.list(outDir).use { dirs ->
      dirs.filter { it.isDirectory() && it.name.endsWith(".sol") }.forEach { dir ->
        Files.list(dir).use { files ->
          files.filter { it.extension == "json" }.forEach { f -> readArtifact(f)?.let { result += it } }
        }
      }
    }
    return result.sortedBy { it.name }
  }

  private fun readArtifact(file: Path): Artifact? = try {
    val json = mapper.readTree(file.readText())
    val abi = json["abi"]
    val target = json["metadata"]?.get("settings")?.get("compilationTarget")?.fields()?.asSequence()?.firstOrNull()
    if (abi == null || target == null) null else {
      val bytecode = json["bytecode"]
      Artifact(
        name = target.value.asText(),
        sourcePath = target.key,
        abiJson = mapper.writeValueAsString(abi),
        bytecode = bytecode?.get("object")?.asText() ?: "0x",
        hasLinkReferences = (bytecode?.get("linkReferences")?.size() ?: 0) > 0,
      )
    }
  } catch (e: Exception) {
    null
  }

  fun isSource(artifact: Artifact) = isSource(artifact.sourcePath)

  /** Under the configured `src` and not a test or script, i.e. something you'd deploy. */
  fun isSource(relativePath: String, config: ForgeConfig = config()): Boolean {
    if (relativePath.endsWith(".t.sol") || relativePath.endsWith(".s.sol")) return false
    return relativePath.startsWith(config.src.trimEnd('/') + "/")
  }

  /** Runs a Foundry tool (or solc) in [root]; throws [FoundryException] when it is missing or times out. */
  fun exec(command: List<String>, timeoutMs: Int, stdin: ByteArray? = null): ProcessOutput {
    if (!File(command[0]).exists()) throw FoundryException("${File(command[0]).name} not found at ${command[0]}. Install Foundry or set its path in Settings | Solidity")
    val cmd = GeneralCommandLine(command).withWorkDirectory(root.toFile()).withCharset(Charsets.UTF_8)
      .withEnvironment("NO_COLOR", "1")
    val handler = CapturingProcessHandler(cmd)
    stdin?.let { bytes -> handler.processInput.use { it.write(bytes) } }
    val output = handler.runProcess(timeoutMs)
    if (output.isTimeout) throw FoundryException("${File(command[0]).name} ${command.getOrNull(1).orEmpty()} timed out")
    return output
  }

  private fun JsonNode.toCompilerMessage(): CompilerMessage {
    val loc = this["sourceLocation"]
    return CompilerMessage(
      file = loc?.get("file")?.asText(),
      start = loc?.get("start")?.asInt() ?: -1,
      end = loc?.get("end")?.asInt() ?: -1,
      severity = this["severity"]?.asText() ?: "error",
      type = this["type"]?.asText() ?: "Error",
      message = this["message"]?.asText() ?: "",
      formatted = this["formattedMessage"]?.asText() ?: this["message"]?.asText() ?: "",
    )
  }

  companion object {
    private val mapper = ObjectMapper()
    private val configCache = ConcurrentHashMap<Path, Pair<List<Long>, Result<ForgeConfig>>>()
    private val loading = ConcurrentHashMap.newKeySet<Path>()

    fun forgePath(project: Project): String {
      val s = SoliditySettings.getInstance(project)
      return resolveForgeExecutable(s.testFoundryExecutablePath, s.testFoundryConfigurationMode, SystemInfo.isWindows)
    }

    /** anvil sits next to forge in a Foundry install. */
    fun anvilPath(project: Project): String =
      Path.of(forgePath(project)).resolveSibling(if (SystemInfo.isWindows) "anvil.exe" else "anvil").toString()

    /** The Foundry root for [file], or for the project when [file] is null. */
    fun find(project: Project, file: VirtualFile? = null): FoundryProject? {
      val root = file?.let { rootOf(it) } ?: projectRoot(project) ?: return null
      return FoundryProject(Path.of(root.path), forgePath(project))
    }

    fun rootOf(file: VirtualFile): VirtualFile? {
      var dir: VirtualFile? = if (file.isDirectory) file else file.parent
      while (dir != null) {
        if (dir.findChild("foundry.toml") != null) return dir
        dir = dir.parent
      }
      return null
    }

    private fun projectRoot(project: Project): VirtualFile? {
      val base = project.guessProjectDir() ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
      if (base.findChild("foundry.toml") != null) return base
      return base.children.firstOrNull { it.isDirectory && it.findChild("foundry.toml") != null }
    }
  }
}
