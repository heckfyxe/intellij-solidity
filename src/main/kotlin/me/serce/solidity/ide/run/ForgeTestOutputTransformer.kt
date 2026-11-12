package me.serce.solidity.ide.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.util.Key
import java.io.File

class ForgeTestTransformingProcessHandler(cmdLine: GeneralCommandLine) : OSProcessHandler(cmdLine) {
  val workDirectory: File? = cmdLine.workDirectory
  private val transformer = ForgeTestOutputTransformer(workDirectory)

  override fun notifyTextAvailable(text: String, outputType: Key<*>) {
    transformer.transformLine(text).forEach {
      super.notifyTextAvailable(it, outputType)
    }
  }
}

/**
 * Turns `forge test` text output into TeamCity service messages for the test tree.
 *
 * Every line is kept: lines of a test (traces, logs, backtraces) go to that test's output, everything else
 * (compiler warnings and errors, the failing tests summary, the fuzz seed) to the run's console.
 * A test ends at the next blank line, test, suite or summary.
 */
class ForgeTestOutputTransformer(private val root: File? = null) {
  private val suiteStartRegex = Regex("^Ran \\d+ tests? for (.+):(\\w+)$")
  private val suiteEndRegex = Regex("^Suite result: \\w+\\. .*finished in ([\\d.]+)(\\S+) \\(.*\\)$")

  // `[FAIL: big; counterexample: ... args=[1000]] testFuzz_a(uint256) (runs: 0, μ: 0, ~: 0)`: the reason may
  // contain `]`, so it is matched greedily up to the last `] name(`
  private val testRegex = Regex("^\\[(PASS|FAIL|SKIP)(?:: (.*))?] (\\w+)\\(([^)]*)\\) \\((.*)\\)$")
  private val summaryRegex = Regex("^Ran \\d+ test suites? in .*$")

  var suiteName = ""
    private set
  private var suitePath = ""
  var activeTest = ""
    private set
  var allTestsRan = false
    private set

  fun transformLine(text: String): List<String> {
    val line = text.trimEnd('\n', '\r')
    if (allTestsRan) return listOf("$line\n")

    if (summaryRegex.matches(line)) {
      allTestsRan = true
      return finishTest() + "$line\n"
    }

    suiteStartRegex.matchEntire(line)?.let { m ->
      val (path, contract) = m.destructured
      val out = finishTest()
      suiteName = contract
      suitePath = path
      return out + message("testSuiteStarted", "name" to contract, "locationHint" to locationHint(contract))
    }

    suiteEndRegex.matchEntire(line)?.let { m ->
      val (duration, unit) = m.destructured
      return finishTest() + message("testSuiteFinished", "name" to suiteName, "duration" to millis(duration, unit))
    }

    testRegex.matchEntire(line)?.let { m ->
      val (status, reason, function, params) = m.destructured
      val out = finishTest().toMutableList()
      activeTest = "$function($params)"
      out += message(
        "testStarted", "name" to activeTest, "captureStandardOutput" to "true",
        "locationHint" to locationHint(suiteName, function)
      )
      when (status) {
        "FAIL" -> out += message("testFailed", "name" to activeTest, "message" to reason.ifEmpty { "failed" }, "details" to line)
        "SKIP" -> out += message("testIgnored", "name" to activeTest, "message" to reason.ifEmpty { "skipped" })
      }
      out += "$line\n"
      return out
    }

    if (activeTest.isNotEmpty() && line.isEmpty()) return finishTest() + "\n"
    return listOf("$line\n")
  }

  private fun finishTest(): List<String> {
    if (activeTest.isEmpty()) return emptyList()
    val out = message("testFinished", "name" to activeTest)
    activeTest = ""
    return listOf(out)
  }

  private fun locationHint(contract: String, function: String? = null): String {
    if (suitePath.isEmpty()) return ""
    val file = File(suitePath).let { if (it.isAbsolute || root == null) it else File(root, suitePath) }
    return ForgeTestLocator.hint(file.path, contract, function)
  }

  private fun millis(duration: String, unit: String): String {
    val value = duration.toDouble()
    val ms = when (unit) {
      "ns" -> value / 1_000_000
      "µs", "us" -> value / 1_000
      "ms" -> value
      "s" -> value * 1_000
      "m" -> value * 60_000
      "h" -> value * 3_600_000
      else -> value
    }
    return "%.0f".format(ms)
  }

  private fun message(name: String, vararg attributes: Pair<String, String>): String =
    attributes.filter { it.second.isNotEmpty() || it.first == "name" }
      .joinToString(" ", "##teamcity[$name ", "]\n") { (key, value) -> "$key='${escapeTeamCity(value)}'" }

  /** TeamCity messages need `|`, quotes, line breaks and brackets escaped. */
  fun escapeTeamCity(s: String): String {
    return s
      .replace("|", "||")
      .replace("'", "|'")
      .replace("\n", "|n")
      .replace("\r", "|r")
      .replace("[", "|[")
      .replace("]", "|]")
  }
}
