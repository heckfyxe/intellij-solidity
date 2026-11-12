package me.serce.solidity.lang.core

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import me.serce.solidity.lang.psi.SolPragmaDirective

data class SolidityVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SolidityVersion> {
  override fun compareTo(other: SolidityVersion): Int =
    compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

  fun nextPatch() = SolidityVersion(major, minor, patch + 1)

  override fun toString() = "$major.$minor.$patch"

  companion object {
    val ZERO = SolidityVersion(0, 0, 0)

    private val FULL = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** Parses a full `major.minor.patch` version, e.g. `0.8.20`. */
    fun parse(text: String): SolidityVersion? {
      val m = FULL.matchEntire(text.trim()) ?: return null
      val (a, b, c) = m.destructured
      return SolidityVersion(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null)
    }

    fun of(text: String): SolidityVersion = parse(text) ?: error("Invalid Solidity version: $text")
  }
}

/**
 * The set of compiler versions a source unit allows, approximated by its hull `[min, maxExclusive)`.
 * [maxExclusive] is `null` when there is no upper bound.
 */
data class SolidityVersionRange(val min: SolidityVersion, val maxExclusive: SolidityVersion?) {
  val isEmpty: Boolean get() = maxExclusive != null && maxExclusive <= min

  /** Every allowed version is `>= version`. */
  fun isAtLeast(version: SolidityVersion): Boolean = !isEmpty && min >= version
  fun isAtLeast(version: String): Boolean = isAtLeast(SolidityVersion.of(version))

  /** Some allowed version is `>= version`. */
  fun canBeAtLeast(version: SolidityVersion): Boolean = !isEmpty && (maxExclusive == null || maxExclusive > version)
  fun canBeAtLeast(version: String): Boolean = canBeAtLeast(SolidityVersion.of(version))

  /** Every allowed version is `< version`. */
  fun isBelow(version: SolidityVersion): Boolean = !isEmpty && maxExclusive != null && maxExclusive <= version
  fun isBelow(version: String): Boolean = isBelow(SolidityVersion.of(version))

  fun intersect(other: SolidityVersionRange): SolidityVersionRange = SolidityVersionRange(
    maxOf(min, other.min),
    when {
      maxExclusive == null -> other.maxExclusive
      other.maxExclusive == null -> maxExclusive
      else -> minOf(maxExclusive, other.maxExclusive)
    }
  )

  fun hull(other: SolidityVersionRange): SolidityVersionRange = when {
    isEmpty -> other
    other.isEmpty -> this
    else -> SolidityVersionRange(
      minOf(min, other.min),
      if (maxExclusive == null || other.maxExclusive == null) null else maxOf(maxExclusive, other.maxExclusive)
    )
  }

  override fun toString() = ">=$min" + (maxExclusive?.let { " <$it" } ?: "")

  companion object {
    val ANY = SolidityVersionRange(SolidityVersion.ZERO, null)

    private val COMPARATOR = Regex("""(\^|~|>=|<=|>|<|=)?\s*v?(\d+|[xX*])(?:\.(\d+|[xX*]))?(?:\.(\d+|[xX*]))?(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")
    private val HYPHEN = Regex("""^(\S+)\s+-\s+(\S+)$""")

    /**
     * Parses a `pragma solidity` version expression (npm semver syntax, as accepted by solc):
     * `0.8.24`, `^0.8.20`, `~0.7.0`, `>=0.8.0 <0.9.0`, `0.8.0 - 0.8.20`, `0.8.x`, alternatives joined with `||`.
     * Returns `null` when the expression can't be understood.
     */
    fun parse(text: String): SolidityVersionRange? {
      val alternatives = text.split("||").map { it.trim() }
      if (alternatives.isEmpty()) return null
      var result: SolidityVersionRange? = null
      for (alternative in alternatives) {
        val range = parseAlternative(alternative) ?: return null
        result = result?.hull(range) ?: range
      }
      return result
    }

    private fun parseAlternative(text: String): SolidityVersionRange? {
      if (text.isEmpty()) return ANY
      HYPHEN.matchEntire(text)?.let { m ->
        val from = parseComparator(">=" + m.groupValues[1]) ?: return null
        val to = parseComparator("<=" + m.groupValues[2]) ?: return null
        return from.intersect(to)
      }
      var range = ANY
      var pos = 0
      while (pos < text.length) {
        if (text[pos].isWhitespace()) {
          pos++
          continue
        }
        val m = COMPARATOR.matchAt(text, pos) ?: return null
        val end = m.range.last + 1
        if (end < text.length && !text[end].isWhitespace()) return null
        range = range.intersect(parseComparator(m.value) ?: return null)
        pos = end
      }
      return range
    }

    private fun parseComparator(text: String): SolidityVersionRange? {
      val m = COMPARATOR.matchEntire(text.trim()) ?: return null
      val op = m.groupValues[1]
      val parts = (2..4).map { i -> m.groupValues[i].takeIf { it.isNotEmpty() && it[0].isDigit() }?.toIntOrNull() }
      // a wildcard component makes all following components wildcards too
      val firstWildcard = parts.indexOfFirst { it == null }
      val major = parts[0]
      val minor = if (firstWildcard in 0..1) null else parts[1]
      val patch = if (firstWildcard in 0..2) null else parts[2]

      if (major == null) {
        // `*`, `x`, `>=*`, ... match everything; `<*`/`>*` match nothing
        return if (op == "<" || op == ">") SolidityVersionRange(SolidityVersion.ZERO, SolidityVersion.ZERO) else ANY
      }
      val lower = SolidityVersion(major, minor ?: 0, patch ?: 0)
      // exclusive upper bound of the X-range denoted by the partial version
      val xUpper = when {
        minor == null -> SolidityVersion(major + 1, 0, 0)
        patch == null -> SolidityVersion(major, minor + 1, 0)
        else -> lower.nextPatch()
      }
      return when (op) {
        "", "=" -> SolidityVersionRange(lower, xUpper)
        ">=" -> SolidityVersionRange(lower, null)
        ">" -> SolidityVersionRange(xUpper, null)
        "<" -> SolidityVersionRange(SolidityVersion.ZERO, lower)
        "<=" -> SolidityVersionRange(SolidityVersion.ZERO, xUpper)
        "~" -> SolidityVersionRange(
          lower,
          if (minor == null) SolidityVersion(major + 1, 0, 0) else SolidityVersion(major, minor + 1, 0)
        )
        "^" -> SolidityVersionRange(
          lower,
          when {
            major > 0 || minor == null -> SolidityVersion(major + 1, 0, 0)
            minor > 0 || patch == null -> SolidityVersion(0, minor + 1, 0)
            else -> SolidityVersion(0, 0, patch + 1)
          }
        )
        else -> null
      }
    }
  }
}

/**
 * The compiler versions allowed by all `pragma solidity` directives of this file,
 * or `null` if the file has no (understandable) version pragma.
 */
fun PsiFile.solidityVersionRange(): SolidityVersionRange? {
  if (this !is SolidityFile) return null
  return CachedValuesManager.getCachedValue(this) {
    CachedValueProvider.Result.create(computeVersionRange(this), this)
  }
}

private fun computeVersionRange(file: SolidityFile): SolidityVersionRange? {
  var result: SolidityVersionRange? = null
  for (child in file.children) {
    if (child !is SolPragmaDirective || child.identifier.text != "solidity") continue
    val range = SolidityVersionRange.parse(child.pragmaAll?.text ?: continue) ?: continue
    result = result?.intersect(range) ?: range
  }
  return result
}

/** The pragma of the containing file guarantees a compiler version `>= version`. */
fun PsiElement.isSolidityVersionAtLeast(version: SolidityVersion): Boolean =
  containingFile?.solidityVersionRange()?.isAtLeast(version) == true

fun PsiElement.isSolidityVersionAtLeast(version: String): Boolean = isSolidityVersionAtLeast(SolidityVersion.of(version))
