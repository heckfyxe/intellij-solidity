package me.serce.solidity.ide.run

import com.intellij.icons.AllIcons
import me.serce.solidity.utils.SolTestBase

class ForgeTestLineMarkerProviderTest : SolTestBase() {
  fun testGutterIconsForTestFile() {
    InlineFile("""
        //- Counter.t.sol
        import "forge-std/Test.sol";

        contract CounterTest is Test {
            function testIncrement() public {
                assertTrue(true);
            }

            function testDecrement() public {
                // some test logic
            }

            function helperFunction() internal {
                // not a test
            }
        }/*caret*/
    """, name = "Counter.t.sol").withCaret()

    val gutters = myFixture.findAllGutters()
    assertEquals(3, gutters.size) // 1 for contract, 2 for test functions

    val contractGutter = gutters.find { it.tooltipText == "Run Forge Tests in CounterTest" }
    assertNotNull(contractGutter)
    assertEquals(AllIcons.RunConfigurations.TestState.Run, contractGutter?.icon)

    val testIncrementGutter = gutters.find { it.tooltipText == "Run Forge Test testIncrement" }
    assertNotNull(testIncrementGutter)

    val testDecrementGutter = gutters.find { it.tooltipText == "Run Forge Test testDecrement" }
    assertNotNull(testDecrementGutter)
  }

  fun testNoGutterIconsForNonTestFile() {
    InlineFile("""
        //- Counter.sol
        contract Counter {
            uint256 public number;

            function setNumber(uint256 newNumber) public {
                number = newNumber;
            }

            function increment() public {
                number++;
            }
        }/*caret*/
    """, name = "Counter.sol").withCaret()

    val gutters = myFixture.findAllGutters()
    assertTrue(gutters.isEmpty())
  }

  fun testGutterIconsForPublicAndExternalFunctionsOnly() {
    InlineFile("""
        //- Visibility.t.sol
        import "forge-std/Test.sol";

        contract VisibilityTest is Test {
            function testPublic() public {}
            function testExternal() external {}
            function testInternal() internal {}
            function testPrivate() private {}
            function testNoVisibility() {} // defaults to public
        }/*caret*/
    """, name = "Visibility.t.sol").withCaret()

    val gutters = myFixture.findAllGutters()
      .filter { it.tooltipText?.startsWith("Run Forge Test") == true && it.tooltipText?.startsWith("Run Forge Tests in") != true }
      .map { it.tooltipText }

    // Check that we have gutter icons for the expected functions
    assertTrue(gutters.contains("Run Forge Test testPublic"))
    assertTrue(gutters.contains("Run Forge Test testExternal"))
    assertTrue(gutters.contains("Run Forge Test testNoVisibility"))
    assertEquals(3, gutters.size)
  }

  fun testGutterIconsForInvariantsAndOnlyForTestContracts() {
    InlineFile("""
        contract Handler {
            function deposit() public {}
        }

        contract VaultInvariants {
            function invariant_solvent() public {}
            function testFuzz_deposit(uint256 amount) public {}
        }/*caret*/
    """, name = "Vault.t.sol").withCaret()

    val tooltips = myFixture.findAllGutters().map { it.tooltipText }.toSet()
    assertEquals(
      setOf("Run Forge Tests in VaultInvariants", "Run Forge Test invariant_solvent", "Run Forge Test testFuzz_deposit"),
      tooltips
    )
  }
}

class SolidityFileLocationFilterTest : me.serce.solidity.utils.SolTestBase() {
  fun testLinksCompilerLocations() {
    val file = myFixture.addFileToProject("test/B.t.sol", "contract B {}").virtualFile
    val root = file.parent.parent
    val line = " --> test/B.t.sol:8:33:"
    val result = SolidityFileLocationFilter(project, root).applyFilter(line, line.length)!!
    val item = result.resultItems.single()
    assertEquals("test/B.t.sol:8:33", line.substring(item.highlightStartOffset, item.highlightEndOffset))
    assertNull(SolidityFileLocationFilter(project, root).applyFilter("no location here", 16))
  }
}
