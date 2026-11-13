package me.serce.solidity.ide.coverage

enum class LineCoverage { COVERED, PARTIAL, UNCOVERED }

/** The coverage of one source file; lines are 1-based as in lcov. */
class FileCoverage(
  /** Line to execution count (`DA:`). */
  val lineHits: Map<Int, Long>,
  /** Line to (taken, total) branch counts (`BRDA:`). */
  val branches: Map<Int, Pair<Int, Int>>,
) {
  fun state(line: Int): LineCoverage? {
    val hits = lineHits[line]
    val branch = branches[line]
    return when {
      hits == null && branch == null -> null
      (hits ?: 0) == 0L && (branch == null || branch.first == 0) -> LineCoverage.UNCOVERED
      branch != null && branch.first < branch.second -> LineCoverage.PARTIAL
      else -> LineCoverage.COVERED
    }
  }

  /** What the gutter marker of [line] says: hits and branches, or that it never ran. */
  fun tooltip(line: Int): String {
    val hits = lineHits[line] ?: 0L
    val branch = branches[line]
    if (state(line) == LineCoverage.UNCOVERED) return "Not covered"
    val parts = mutableListOf(if (hits == 1L) "Hit once" else "Hits: $hits")
    if (branch != null) parts += "branches: ${branch.first} of ${branch.second} taken"
    return parts.joinToString(", ")
  }

  /** Every line lcov reported, with its state. */
  fun states(): Map<Int, LineCoverage> = (lineHits.keys + branches.keys).associateWith { state(it)!! }
}

/** Line and branch totals of a report, as `forge coverage --report summary` counts them. */
class CoverageSummary(val linesHit: Int, val lines: Int, val branchesHit: Int, val branches: Int) {
  private fun percent(hit: Int, total: Int) = if (total == 0) "n/a" else "%.1f%%".format(java.util.Locale.ROOT, hit * 100.0 / total)

  override fun toString(): String =
    "Lines: ${percent(linesHit, lines)} ($linesHit/$lines)" +
      if (branches == 0) "" else ", branches: ${percent(branchesHit, branches)} ($branchesHit/$branches)"

  companion object {
    fun of(report: Map<String, FileCoverage>): CoverageSummary {
      val files = report.values
      return CoverageSummary(
        linesHit = files.sumOf { f -> f.lineHits.values.count { it > 0 } },
        lines = files.sumOf { it.lineHits.size },
        branchesHit = files.sumOf { f -> f.branches.values.sumOf { it.first } },
        branches = files.sumOf { f -> f.branches.values.sumOf { it.second } },
      )
    }
  }
}

/** Reads the lcov tracefile `forge coverage --report lcov` writes. */
object Lcov {
  /** Source path as written in `SF:` (relative to the Foundry root for forge) to its coverage. */
  fun parse(text: String): Map<String, FileCoverage> {
    val result = LinkedHashMap<String, FileCoverage>()
    var file: String? = null
    var hits = HashMap<Int, Long>()
    var branches = HashMap<Int, Pair<Int, Int>>()
    fun flush() {
      val f = file ?: return
      val previous = result[f]
      if (previous != null) { // the same file in several records: merge
        previous.lineHits.forEach { (l, h) -> hits.merge(l, h, Long::plus) }
        previous.branches.forEach { (l, b) -> branches.merge(l, b) { a, c -> (a.first + c.first) to (a.second + c.second) } }
      }
      result[f] = FileCoverage(hits, branches)
      file = null
      hits = HashMap()
      branches = HashMap()
    }
    for (raw in text.lineSequence()) {
      val line = raw.trim()
      when {
        line.startsWith("SF:") -> { flush(); file = line.removePrefix("SF:").replace('\\', '/') }
        line == "end_of_record" -> flush()
        line.startsWith("DA:") -> {
          val parts = line.removePrefix("DA:").split(',')
          val l = parts.getOrNull(0)?.toIntOrNull() ?: continue
          val h = parts.getOrNull(1)?.toLongOrNull() ?: continue
          hits.merge(l, h, Long::plus)
        }
        line.startsWith("BRDA:") -> {
          // BRDA:<line>,<block>,<branch>,<taken>; taken is `-` when the block never ran
          val parts = line.removePrefix("BRDA:").split(',')
          val l = parts.getOrNull(0)?.toIntOrNull() ?: continue
          val taken = parts.getOrNull(3)?.toLongOrNull() ?: 0L
          branches.merge(l, (if (taken > 0) 1 else 0) to 1) { a, b -> (a.first + b.first) to (a.second + b.second) }
        }
      }
    }
    flush()
    return result
  }
}
