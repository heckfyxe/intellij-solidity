package me.serce.solidity.chain.foundry

import me.serce.solidity.util.text
import me.serce.solidity.util.Json
import com.fasterxml.jackson.databind.JsonNode
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** [cancelled] when the user stopped the tool, which needs no error message. */
class FoundryException(message: String, val cancelled: Boolean = false) : Exception(message)

/** The JSON artifacts forge wrote into [outDir]: `<Source>.sol/<Contract>.json`. */
fun artifactFiles(outDir: Path): List<Path> {
  if (!outDir.isDirectory()) return emptyList()
  return Files.list(outDir).use { dirs ->
    dirs.filter { it.isDirectory() && it.name.endsWith(".sol") }.toList().flatMap { dir ->
      Files.list(dir).use { files -> files.filter { it.extension == "json" }.toList() }
    }
  }
}

/** The source path (key) and contract name (value) an artifact was compiled for. */
fun JsonNode.compilationTarget(): Map.Entry<String, JsonNode>? =
  this["metadata"]?.get("settings")?.get("compilationTarget")?.fields()?.asSequence()?.firstOrNull()

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
  /** forge's `evm_version`; forge lowers it to what the chosen solc supports, see `SolcRequest.normalizeEvmVersion`. */
  val evmVersion: String? = null,
  val viaIr: Boolean = false,
  val optimizer: Boolean = false,
  val optimizerRuns: Int = 200,
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
class FoundryProject(val root: Path, private val forgeExe: String) {
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

  private fun stamp() = listOf(CONFIG_FILE, "remappings.txt").map { root.resolve(it).toFile().lastModified() }

  private fun readConfig(): ForgeConfig {
    val out = forge(listOf("config", "--json"), timeoutMs = 30_000).let { o ->
      if (o.exitCode != 0) throw FoundryException("forge config failed: ${ToolOutput.errorText(o)}")
      o.stdout + o.stderr
    }
    return parseConfig(out.substring(out.indexOf('{')))
  }

  fun build(): BuildResult {
    val output = forge(listOf("build", "--json"), timeoutMs = 600_000)
    val code = output.exitCode
    val out = output.stdout + output.stderr
    val jsonStart = out.indexOf("{\n").takeIf { it >= 0 } ?: out.indexOf('{')
    val json = if (jsonStart >= 0) runCatching { Json.mapper.readTree(out.substring(jsonStart)) }.getOrNull() else null
    val messages = json?.get("errors")?.map { it.toCompilerMessage() } ?: emptyList()
    val success = code == 0 && messages.none { it.severity == "error" }
    if (!success && messages.isEmpty()) {
      return BuildResult(false, listOf(CompilerMessage(null, 0, 0, "error", "Error", out.trim().ifEmpty { "forge build failed" }, out)))
    }
    return BuildResult(success, messages)
  }

  /** What forge last compiled into the configured `out` directory; parsed once per build. */
  fun output(): ForgeOutput = ForgeOutput.of(root, root.resolve(config().out))

  fun artifacts(): List<Artifact> = output().artifacts

  /** Under the configured `src` and not a test or script, i.e. something you'd deploy. */
  fun isSource(relativePath: String, config: ForgeConfig = config()): Boolean {
    if (relativePath.endsWith(".t.sol") || relativePath.endsWith(".s.sol")) return false
    return relativePath.startsWith(config.src.trimEnd('/') + "/")
  }

  /** `forge inspect <path>:<name> storageLayout --json`: the storage layout solc reports for a compiled contract. */
  fun storageLayout(sourcePath: String, contract: String): String {
    val out = forge(listOf("inspect", "$sourcePath:$contract", "storageLayout", "--json"), 180_000)
    if (out.exitCode != 0) throw FoundryException(ToolOutput.errorText(out))
    return out.stdout
  }

  /** Runs `forge <args>` in [workDir]; throws [FoundryException] when forge is missing, times out or is cancelled. */
  fun forge(args: List<String>, timeoutMs: Int, workDir: Path = root): ProcessOutput =
    FoundryTools.run(FoundryTools.commandLine(listOf(forgeExe) + args, workDir), timeoutMs)

  private fun JsonNode.toCompilerMessage(): CompilerMessage {
    val loc = this["sourceLocation"]
    return CompilerMessage(
      file = loc?.text("file"),
      start = loc?.get("start")?.asInt() ?: -1,
      end = loc?.get("end")?.asInt() ?: -1,
      severity = text("severity") ?: "error",
      type = text("type") ?: "Error",
      message = text("message") ?: "",
      formatted = text("formattedMessage") ?: text("message") ?: "",
    )
  }

  companion object {
    const val CONFIG_FILE = "foundry.toml"
    private val configCache = ConcurrentHashMap<Path, Pair<List<Long>, Result<ForgeConfig>>>()
    private val loading = ConcurrentHashMap.newKeySet<Path>()

    /** Parses `forge config --json` output. */
    fun parseConfig(jsonText: String): ForgeConfig {
      val json = Json.mapper.readTree(jsonText)
      return ForgeConfig(
        src = json.text("src") ?: "src",
        out = json.text("out") ?: "out",
        libs = json["libs"]?.map { it.asText() } ?: listOf("lib"),
        remappings = json["remappings"]?.map { it.asText() } ?: emptyList(),
        solc = (json["solc"]?.takeIf { !it.isNull } ?: json["solc_version"]?.takeIf { !it.isNull })?.asText(),
        evmVersion = json["evm_version"]?.takeIf { it.isTextual }?.asText(),
        viaIr = json["via_ir"]?.asBoolean() ?: false,
        optimizer = json["optimizer"]?.asBoolean() ?: false,
        optimizerRuns = json["optimizer_runs"]?.takeIf { it.canConvertToInt() }?.asInt() ?: 200,
      )
    }

    /** forge as configured in the settings, or `~/.foundry/bin/forge`. */
    fun forgePath(project: Project, isWindows: Boolean = SystemInfo.isWindows): String {
      val s = SoliditySettings.getInstance(project)
      return resolveForgeExecutable(s.testFoundryExecutablePath, s.testFoundryConfigurationMode, isWindows)
    }

    /** Another Foundry tool (`anvil`, `cast`): they sit next to forge in a Foundry install. */
    fun toolPath(project: Project, tool: String): String =
      Path.of(forgePath(project)).resolveSibling(if (SystemInfo.isWindows) "$tool.exe" else tool).toString()

    fun anvilPath(project: Project): String = toolPath(project, "anvil")

    /** The Foundry root for [file], or for the project when [file] is null. */
    fun find(project: Project, file: VirtualFile? = null): FoundryProject? {
      val root = file?.let { rootOf(it) } ?: projectRoot(project) ?: return null
      return FoundryProject(Path.of(root.path), forgePath(project))
    }

    fun rootOf(file: VirtualFile): VirtualFile? {
      var dir: VirtualFile? = if (file.isDirectory) file else file.parent
      while (dir != null) {
        if (dir.findChild(CONFIG_FILE) != null) return dir
        dir = dir.parent
      }
      return null
    }

    private fun projectRoot(project: Project): VirtualFile? {
      val base = project.guessProjectDir() ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
      if (base.findChild(CONFIG_FILE) != null) return base
      return base.children.firstOrNull { it.isDirectory && it.findChild(CONFIG_FILE) != null }
    }
  }
}
