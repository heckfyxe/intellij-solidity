package me.serce.solidity.lang.resolve.ref

import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.psi.PsiManager
import me.serce.solidity.chain.foundry.ForgeConfig
import me.serce.solidity.chain.foundry.FoundryProject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SolImportConfigService(val project: Project) {

  /** `context:prefix=target`; the remapping applies only to files under [context] (relative to the root). */
  internal data class Remapping(val context: String, val prefix: String, val target: String) {
    companion object {
      fun parse(text: String): Remapping? {
        val (left, target) = text.trim().trim('"').split("=", limit = 2).takeIf { it.size == 2 } ?: return null
        val context = left.substringBefore(':', "").trim()
        val prefix = left.substringAfter(':').trim()
        if (prefix.isEmpty()) return null
        return Remapping(context, prefix, target.trim())
      }
    }
  }

  private data class FoundryImportConfig(
    val remappings: List<Remapping>,
    val reverseRemappings: Map<String, String>,
    /** Directories dependencies are installed in: forge's `libs`, and Soldeer's `dependencies`. */
    val libs: List<String> = DEFAULT_LIBS,
  )

  private data class CachedConfig(
    val remappingsStamp: Long?,
    val foundryStamp: Long?,
    /** The config forge reported, compared by identity: a newer one replaces the remappings parsed here. */
    val forgeConfig: ForgeConfig?,
    val config: FoundryImportConfig,
  )

  private val rootToConfig = ConcurrentHashMap<String, CachedConfig>()
  private val emptyConfig = FoundryImportConfig(emptyList(), emptyMap())
  private val tomlMapper by lazy { TomlMapper() }

  fun resolve(path: String, fromFile: VirtualFile): VirtualFile? {
    if (!fromFile.isValid) {
      return null
    }
    var current: VirtualFile? = when {
        fromFile.isDirectory -> fromFile
        else -> fromFile.parent
    }
    while (current != null) {
      if (!current.isValid) return null
      val config = when {
        hasFoundryConfig(current) -> getOrLoadConfig(current)
        else -> emptyConfig
      }

      val remappedPath = applyRemappings(config.remappings, path, relativeTo(fromFile, current))
      val remappedFile = findFile(current, remappedPath)
      if (remappedFile != null) {
        return remappedFile
      }

      val fallbackFile = foundryDefaultFallback(current, path, config.libs)
      if (fallbackFile != null) {
        return fallbackFile
      }

      current = current.parent
    }
    return null
  }

  fun resolveRemapping(path: String, fromFile: VirtualFile): VirtualFile? {
    if (!fromFile.isValid) return null
    var current: VirtualFile? = if (fromFile.isDirectory) fromFile else fromFile.parent
    while (current != null) {
      if (hasFoundryConfig(current)) {
        val remappedPath = applyRemappings(getOrLoadConfig(current).remappings, path, relativeTo(fromFile, current))
        if (remappedPath != path) {
          findFile(current, remappedPath)?.let { return it }
        }
      }
      current = current.parent
    }
    return null
  }

  fun reverseRemappings(fromFile: VirtualFile): Map<String, String> {
    if (!fromFile.isValid) {
      return emptyMap()
    }
    val fromDir = when {
      fromFile.isDirectory -> fromFile
      else -> fromFile.parent ?: return emptyMap()
    }
    val foundryRoot = findFoundryRoot(fromDir) ?: return emptyMap()
    return getOrLoadConfig(foundryRoot).reverseRemappings
  }

  private fun getOrLoadConfig(foundryRoot: VirtualFile): FoundryImportConfig {
    val remappingsFile = foundryRoot.findChild("remappings.txt")
    val foundryFile = foundryRoot.findChild("foundry.toml")
    val remappingsStamp = remappingsFile?.modificationStamp
    val foundryStamp = foundryFile?.modificationStamp
    val rootPath = foundryRoot.path

    val forgeConfig = forgeConfig(foundryRoot)

    fun CachedConfig.isCurrent(): Boolean =
      this.remappingsStamp == remappingsFile?.modificationStamp && this.foundryStamp == foundryFile?.modificationStamp &&
        this.forgeConfig === forgeConfig

    rootToConfig[rootPath]?.takeIf { it.isCurrent() }?.let { return it.config }

    return rootToConfig.compute(rootPath) { _, guarded ->
      if (guarded != null && guarded.isCurrent()) {
        return@compute guarded
      }
      // forge's own list includes the remappings it detects in lib/ and the other profiles' settings; until it
      // is known (or when forge is missing), read remappings.txt and foundry.toml directly
      val remappings = forgeConfig?.remappings?.mapNotNull(Remapping::parse)
        ?: (parseRemappingsFile(remappingsFile) + remappingsFromFoundryConfigFile(foundryFile))
      CachedConfig(
        remappingsStamp = remappingsStamp,
        foundryStamp = foundryStamp,
        forgeConfig = forgeConfig,
        config = FoundryImportConfig(
          remappings = remappings,
          reverseRemappings = buildReverseRemappings(remappings),
          libs = (forgeConfig?.libs.orEmpty() + DEFAULT_LIBS).distinct(),
        ),
      )
    }!!.config
  }

  /**
   * The config forge reports for [foundryRoot] if it is already known; otherwise starts reading it in the
   * background (never under the read action resolve runs in) and refreshes resolve results once it arrives.
   */
  private fun forgeConfig(foundryRoot: VirtualFile): ForgeConfig? {
    if (ApplicationManager.getApplication().isUnitTestMode || foundryRoot.findChild("foundry.toml") == null) return null
    if (foundryRoot.fileSystem !is LocalFileSystem) return null
    val foundry = FoundryProject(Path.of(foundryRoot.path), FoundryProject.forgePath(project))
    foundry.cachedConfig()?.let { return it.getOrNull() }
    foundry.loadConfigInBackground {
      ApplicationManager.getApplication().invokeLater({
        PsiManager.getInstance(project).dropPsiCaches()
        DaemonCodeAnalyzer.getInstance(project).restart()
      }, project.disposed)
    }
    return null
  }

  private fun relativeTo(file: VirtualFile, root: VirtualFile): String =
    VfsUtilCore.getRelativePath(file, root) ?: ""

  private fun findFile(root: VirtualFile, path: String): VirtualFile? =
    if (path.startsWith("/")) root.fileSystem.findFileByPath(path) else root.findFileByRelativePath(path)

  private fun findFoundryRoot(fromDir: VirtualFile): VirtualFile? {
    var current: VirtualFile? = fromDir
    while (current != null) {
      if (!current.isValid) return null
      if (hasFoundryConfig(current)) {
        return current
      }
      current = current.parent
    }
    return null
  }

  private fun hasFoundryConfig(dir: VirtualFile): Boolean {
    return dir.findChild("remappings.txt") != null || dir.findChild("foundry.toml") != null
  }

  private fun parseRemappingsFile(file: VirtualFile?): List<Remapping> {
    if (file == null) {
      return emptyList()
    }
    val mappingsContents = runCatching {
      file.contentsToByteArray().toString(Charsets.UTF_8).split(newlineRegex)
    }.getOrElse {
      return emptyList()
    }
    return mappingsContents.mapNotNull(Remapping::parse)
  }

  private fun remappingsFromFoundryConfigFile(file: VirtualFile?): List<Remapping> {
    if (file == null) {
      return emptyList()
    }
    val data = runCatching {
      tomlMapper.readTree(file.readText())
    }.getOrNull()
    val remappings = data?.get("profile")?.get("default")?.get("remappings")
    if (remappings == null) {
      return emptyList()
    }
    return remappings
      .filterIsInstance<TextNode>()
      // each is e.g. "forge-std/=lib/forge-std/src/"
      .mapNotNull { Remapping.parse(it.textValue()) }
      // normalize target for import path concatenation
      .map { if (it.target.endsWith("/")) it else it.copy(target = it.target + "/") }
  }

  /** Applies the remapping solc would pick for an import of [path] in the file at [from] (relative to the root). */
  internal fun applyRemappings(remappings: List<Remapping>, path: String, from: String = ""): String {
    val match = remappings
      .withIndex()
      .filter { (_, mapping) -> path.startsWith(mapping.prefix) && from.startsWith(mapping.context) }
      // solc: the longest context, then the longest prefix, then the last one given
      .maxWithOrNull(
        compareBy<IndexedValue<Remapping>> { it.value.context.length }
          .thenBy { it.value.prefix.length }
          .thenBy { it.index }
      )
      ?.value
      ?: return path
    return match.target + path.removePrefix(match.prefix)
  }

  private fun buildReverseRemappings(remappings: List<Remapping>): Map<String, String> {
    val reverse = linkedMapOf<String, String>()
    remappings.filter { it.context.isEmpty() }.forEach { (_, prefix, target) ->
      val trimmedPrefix = prefix.trim()
      val trimmedTarget = target.trim()
      if (trimmedPrefix.isNotEmpty() && trimmedTarget.isNotEmpty()) {
        reverse.putIfAbsent(trimmedTarget, trimmedPrefix)
        reverse.putIfAbsent(trimmedTarget.trimEnd('/'), trimmedPrefix)
      }
    }
    return reverse
  }

  /**
   * Without a remapping, `forge-std/Test.sol` is looked up in each dependency directory as
   * `lib/forge-std/src/Test.sol` or `lib/forge-std/contracts/Test.sol`; Soldeer installs versioned folders
   * (`dependencies/forge-std-1.9.4/`), which match too.
   */
  private fun foundryDefaultFallback(foundryRoot: VirtualFile, path: String, libs: List<String>): VirtualFile? {
    val segments = path.replace("\\", "/").split("/").filter { it.isNotEmpty() }
    if (segments.size < 2) {
      return null
    }
    val libName = segments.first()
    val libFile = segments.drop(1).joinToString("/")
    for (base in libs) {
      val baseDir = foundryRoot.findFileByRelativePath(base) ?: continue
      val candidates = listOfNotNull(baseDir.findChild(libName)) +
        baseDir.children.filter { it.isDirectory && it.name.startsWith("$libName-") && it.name.drop(libName.length + 1).firstOrNull()?.isDigit() == true }
      for (dir in candidates) {
        for (sourceDir in listOf("src/", "contracts/")) {
          dir.findFileByRelativePath(sourceDir + libFile)?.let { return it }
        }
      }
    }
    return null
  }

  companion object {
    private val newlineRegex = "[\r\n]+".toRegex()
    private val DEFAULT_LIBS = listOf("lib", "dependencies")

    @JvmStatic
    fun getInstance(project: Project): SolImportConfigService = project.service()
  }
}
