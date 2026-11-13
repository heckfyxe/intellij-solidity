package me.serce.solidity.ide.run

import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

class ForgeGasSnapshotTest : DeclarativeInlayHintsProviderTestCase() {
  // written by forge 1.9 `forge snapshot`, plus an invariant and a tuple-argument line
  private val snapshot = """
    CounterTest:testFuzz_SetNumber(uint256) (runs: 256, μ: 50109, ~: 52511)
    CounterTest:test_Increment() (gas: 51847)
    CounterTest:test_Pair(uint256,(uint8,address)) (runs: 256, μ: 900, ~: 880)
    CounterTest:invariant_number() (runs: 256, calls: 128000, reverts: 0)
    OtherTest:test_Increment() (gas: 7)
  """.trimIndent()

  fun testParse() {
    val entries = GasSnapshot.parse(snapshot + "\nnot a snapshot line\n")
    assertEquals(5, entries.size)
    assertEquals(GasSnapshotEntry("CounterTest", "testFuzz_SetNumber", "uint256", "runs: 256, μ: 50109, ~: 52511"), entries[0])
    assertEquals(GasSnapshotEntry("CounterTest", "test_Increment", "", "gas: 51847"), entries[1])
    assertEquals("uint256,(uint8,address)", entries[2].parameters)
    assertEquals(2, entries[2].parameterCount)
    assertEquals(0, entries[1].parameterCount)
    assertEquals("runs: 256, calls: 128000, reverts: 0", entries[3].measurement)
  }

  fun testCheckOutput() {
    // forge 1.9 `forge snapshot --check` after a test got more expensive
    val output = """
      Ran 2 tests for test/A.t.sol:ATest
      [PASS] test_A() (gas: 22347)
      [PASS] test_B(uint8) (runs: 256, μ: 20504, ~: 22526)
      Suite result: ok. 2 passed; 0 failed; 0 skipped; finished in 1.86ms (1.74ms CPU time)
      Diff in "ATest::test_A()": consumed "(gas: 22347)" gas, expected "(gas: 22234)" gas 
    """.trimIndent()
    assertEquals(listOf(GasSnapshotDiff("ATest::test_A()", "gas: 22347", "gas: 22234")), ForgeSnapshotOutput.diffs(output))
    assertEmpty(ForgeSnapshotOutput.diffs("Suite result: ok."))
  }

  fun testFindTellsOverloadsApart() {
    val entries = GasSnapshot.parse("A:test_x() (gas: 1)\nA:test_x(uint256) (runs: 256, μ: 2, ~: 2)")
    assertEquals("gas: 1", GasSnapshot.find(entries, "A", "test_x", 0)?.measurement)
    assertEquals("runs: 256, μ: 2, ~: 2", GasSnapshot.find(entries, "A", "test_x", 1)?.measurement)
    assertNull(GasSnapshot.find(entries, "B", "test_x", 0))
  }

  fun testHintsNextToTestFunctions() {
    myFixture.addFileToProject("foundry.toml", "[profile.default]\n")
    myFixture.addFileToProject(GasSnapshot.FILE_NAME, snapshot)
    doTestProvider(
      "Counter.t.sol",
      """
        contract CounterTest {
            function setUp() public {}

            function test_Increment() public {/*<# gas: 51847 #>*/
            }

            function testFuzz_SetNumber(uint256 x) public {/*<# runs: 256, μ: 50109, ~: 52511 #>*/
            }

            function test_Missing() public {
            }
        }
      """.trimIndent(),
      ForgeGasSnapshotInlayProvider()
    )
  }

  fun testNoHintsWithoutSnapshot() {
    myFixture.addFileToProject("foundry.toml", "[profile.default]\n")
    doTestProvider(
      "Counter.t.sol",
      """
        contract CounterTest {
            function test_Increment() public {
            }
        }
      """.trimIndent(),
      ForgeGasSnapshotInlayProvider()
    )
  }
}
