package me.serce.solidity.ide.run

import junit.framework.TestCase
import java.io.File

class ForgeTestOutputTransformerTest : TestCase() {
  /** Output of `forge test -vvvv` (forge 1.9) with unit, fuzz, invariant and skipped tests and compiler warnings. */
  private val output = """
    Compiling 3 files with Solc 0.8.38
    Compiler run successful with warnings:
    Warning (2072): Unused local variable.
     --> test/B.t.sol:8:33:

    Ran 1 test for test/Other.sol:OtherTest
    [PASS] testInOther() (gas: 121)
    Traces:
      [121] OtherTest::testInOther()
        └─ ← [Stop]

    Suite result: ok. 1 passed; 0 failed; 0 skipped; finished in 5.37ms (1.75ms CPU time)

    Ran 4 tests for test/A.t.sol:ATest
    [FAIL: failed to set up invariant testing environment: No contracts to fuzz.] invariant_x() (runs: 0, calls: 0, reverts: 0)
    [FAIL: big; counterexample: calldata=0x3c88 args=[1000]] testFuzz_a(uint256) (runs: 0, μ: 0, ~: 0)
    Traces:
      [706] ATest::testFuzz_a(1000)
        └─ ← [Revert] big

    [PASS] testOk() (gas: 187)
    Suite result: FAILED. 1 passed; 2 failed; 0 skipped; finished in 7.30ms (4.54ms CPU time)

    Ran 2 tests for test/B.t.sol:BTest
    [PASS] testFuzz_ok(uint8,address) (runs: 50, μ: 829, ~: 858)
    [SKIP] test_skipped() (gas: 0)
    Suite result: ok. 1 passed; 0 failed; 1 skipped; finished in 2s (7.52ms CPU time)

    Ran 3 test suites in 11.61ms (20.09ms CPU time): 3 tests passed, 2 failed, 1 skipped (6 total tests)

    Failing tests:
    Fuzz seed: 0x2b57 (use `--fuzz-seed` to reproduce)
  """.trimIndent()

  private fun transform(): List<String> {
    val transformer = ForgeTestOutputTransformer(File("/project"))
    return output.lines().flatMap { transformer.transformLine("$it\n") }
  }

  private fun messages() = transform().filter { it.startsWith("##teamcity") }.map { it.trimEnd() }

  fun testTreeEvents() {
    val events = messages().map { it.substringAfter("##teamcity[").substringBefore(" ") + " " + Regex("name='([^']*)'").find(it)!!.groupValues[1] }
    assertEquals(
      listOf(
        "testSuiteStarted OtherTest", "testStarted testInOther()", "testFinished testInOther()", "testSuiteFinished OtherTest",
        "testSuiteStarted ATest",
        "testStarted invariant_x()", "testFailed invariant_x()", "testFinished invariant_x()",
        "testStarted testFuzz_a(uint256)", "testFailed testFuzz_a(uint256)", "testFinished testFuzz_a(uint256)",
        "testStarted testOk()", "testFinished testOk()", "testSuiteFinished ATest",
        "testSuiteStarted BTest",
        "testStarted testFuzz_ok(uint8,address)", "testFinished testFuzz_ok(uint8,address)",
        "testStarted test_skipped()", "testIgnored test_skipped()", "testFinished test_skipped()",
        "testSuiteFinished BTest",
      ),
      events
    )
  }

  fun testFuzzFailureKeepsCounterexample() {
    val failed = messages().first { it.startsWith("##teamcity[testFailed name='testFuzz_a(uint256)'") }
    assertTrue(failed, failed.contains("message='big; counterexample: calldata=0x3c88 args=|[1000|]'"))
  }

  fun testLocationHints() {
    val messages = messages()
    assertTrue(messages.any { it.startsWith("##teamcity[testSuiteStarted name='ATest' locationHint='solidity-test:///project/test/A.t.sol::ATest'") })
    assertTrue(messages.any { it.startsWith("##teamcity[testStarted name='testFuzz_a(uint256)'") && it.contains("locationHint='solidity-test:///project/test/A.t.sol::ATest::testFuzz_a'") })
  }

  fun testDurations() {
    val finished = messages().filter { it.startsWith("##teamcity[testSuiteFinished") }
    assertEquals(listOf("5", "7", "2000"), finished.map { Regex("duration='(\\d+)'").find(it)!!.groupValues[1] })
  }

  fun testKeepsOutputOutsideTests() {
    val plain = transform().filterNot { it.startsWith("##teamcity") }.map { it.trimEnd() }
    assertTrue(plain.contains("Warning (2072): Unused local variable."))
    assertTrue(plain.contains("Fuzz seed: 0x2b57 (use `--fuzz-seed` to reproduce)"))
    assertTrue(plain.contains("  [706] ATest::testFuzz_a(1000)"))
  }

  fun testCompilerFailureIsShown() {
    val transformer = ForgeTestOutputTransformer()
    assertEquals(listOf("Compiler run failed:\n"), transformer.transformLine("Compiler run failed:\n"))
    assertEquals(listOf("Error (7576): Undeclared identifier.\n"), transformer.transformLine("Error (7576): Undeclared identifier.\n"))
  }
}
