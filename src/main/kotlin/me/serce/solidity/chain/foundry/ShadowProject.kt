package me.serce.solidity.chain.foundry

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A throwaway view of a Foundry project for tools that only read files from disk (`forge lint` has no stdin or
 * overlay input): a temp directory of symlinks to the project's entries, except that files with unsaved changes are
 * real files holding the editor text, and the directories on their path are real directories (whose other entries are
 * symlinks again). Build output directories are left out so a tool can't write into the project through a link.
 */
object ShadowProject {
  /**
   * Runs [action] in a shadow of [root] where each relative path in [overrides] has the given text; the shadow is
   * deleted afterwards (links only, never what they point to). Returns null when the shadow can't be created, e.g. on
   * Windows without the right to create symlinks.
   */
  fun <T> run(root: Path, overrides: Map<String, String>, skip: Set<String>, action: (Path) -> T): T? {
    val shadow = Files.createTempDirectory("solidity-shadow")
    try {
      try {
        mirror(root, shadow, overrides, skip + ".git")
      } catch (e: IOException) {
        return null
      } catch (e: UnsupportedOperationException) {
        return null
      }
      return action(shadow)
    } finally {
      deleteShadow(shadow)
    }
  }

  private fun mirror(source: Path, target: Path, overrides: Map<String, String>, skip: Set<String>) {
    val byFirstSegment = overrides.entries.groupBy { it.key.substringBefore('/') }
    val names = HashSet<String>()
    Files.list(source).use { children ->
      for (child in children) {
        val name = child.fileName.toString()
        names += name
        if (name in skip) continue
        val here = byFirstSegment[name]
        when {
          here == null -> Files.createSymbolicLink(target.resolve(name), child)
          here.any { it.key == name } -> Files.writeString(target.resolve(name), here.first { it.key == name }.value)
          else -> {
            val dir = Files.createDirectory(target.resolve(name))
            mirror(child, dir, here.associate { it.key.substringAfter('/') to it.value }, emptySet())
          }
        }
      }
    }
    // files not on disk yet
    for ((name, entries) in byFirstSegment) {
      if (name in names) continue
      for ((path, text) in entries) {
        val file = target.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
      }
    }
  }

  /** Deletes the shadow without following links: a link to a directory is removed as a file. */
  private fun deleteShadow(shadow: Path) {
    try {
      Files.walkFileTree(shadow, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          Files.deleteIfExists(file)
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
          Files.deleteIfExists(dir)
          return FileVisitResult.CONTINUE
        }
      })
    } catch (_: IOException) {
      // a leftover temp directory is harmless
    }
  }
}
