package me.serce.solidity.chain.foundry

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.SystemProperties
import java.io.File

/** Finds the solc binaries Foundry installed (via svm) and picks one for a file's pragma. */
object SolcLocator {
  data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
      fun parse(s: String): Version? {
        val parts = s.trim().removePrefix("v").split('.')
        if (parts.size > 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return Version(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
      }
    }
  }

  private val svmDirs: List<File>
    get() {
      val home = SystemProperties.getUserHome()
      return listOfNotNull(
        System.getenv("SVM_HOME")?.let { File(it) },
        File(home, ".svm"),
        File(home, "Library/Application Support/svm").takeIf { SystemInfo.isMac },
        File(home, ".local/share/svm"),
        System.getenv("APPDATA")?.let { File(it, "svm") },
      )
    }

  @Volatile private var cache: Pair<Long, Map<Version, File>>? = null

  /** Cached for a while: the annotator asks on every highlighting pass. */
  fun installed(): Map<Version, File> {
    cache?.let { (at, map) -> if (System.currentTimeMillis() - at < 60_000) return map }
    return scan().also { cache = System.currentTimeMillis() to it }
  }

  private fun scan(): Map<Version, File> {
    val result = HashMap<Version, File>()
    for (dir in svmDirs) {
      dir.listFiles()?.forEach { vdir ->
        val v = Version.parse(vdir.name) ?: return@forEach
        val exe = File(vdir, "solc-${vdir.name}" + if (SystemInfo.isWindows) ".exe" else "")
        if (exe.canExecute()) result.putIfAbsent(v, exe)
      }
    }
    return result
  }

  /**
   * [configured] is foundry.toml's `solc` (a version or a path); otherwise the newest installed
   * version satisfying the file's `pragma solidity`.
   */
  fun find(configured: String?, source: String): File? {
    if (configured != null) {
      File(configured).takeIf { it.isAbsolute && it.canExecute() }?.let { return it }
      Version.parse(configured)?.let { v -> return installed()[v] }
    }
    val versions = installed()
    val pragma = PRAGMA.find(source)?.groupValues?.get(1)
    val matching = versions.keys.filter { pragma == null || matches(pragma, it) }
    return matching.maxOrNull()?.let { versions[it] }
  }

  private val PRAGMA = Regex("""pragma\s+solidity\s+([^;]+);""")
  private val COMPARATOR = Regex("""(\^|~|>=|<=|>|<|=)?\s*(\d+(?:\.\d+){0,2})""")

  /** npm-style range check as used by solc pragmas: `^0.8.0`, `>=0.8.0 <0.9.0`, `0.8.20 || ^0.7.0`. */
  fun matches(range: String, v: Version): Boolean = range.split("||").any { alt ->
    val comparators = COMPARATOR.findAll(alt).toList()
    comparators.isNotEmpty() && comparators.all { m ->
      val op = m.groupValues[1]
      val partCount = m.groupValues[2].count { it == '.' } + 1
      val target = Version.parse(m.groupValues[2])!!
      val tildeUpper = if (partCount == 1) Version(target.major + 1, 0, 0) else Version(target.major, target.minor + 1, 0)
      when (op) {
        "^" -> v >= target && v < caretUpper(target)
        "~" -> v >= target && v < tildeUpper
        ">=" -> v >= target
        "<=" -> v <= target
        ">" -> v > target
        "<" -> v < target
        else -> if (partCount == 3) v == target else v >= target && v < tildeUpper
      }
    }
  }

  private fun caretUpper(t: Version) = when {
    t.major > 0 -> Version(t.major + 1, 0, 0)
    t.minor > 0 -> Version(0, t.minor + 1, 0)
    else -> Version(0, 0, t.patch + 1)
  }
}
