package me.serce.solidity.ide.refactoring

import com.intellij.refactoring.util.CommonRefactoringUtil
import me.serce.solidity.utils.SolTestBase

class SolLocalVariableRefactoringsTest : SolTestBase() {
  private fun introduce(before: String, after: String) {
    myFixture.configureByText("a.sol", before)
    SolIntroduceVariableHandler().invoke(project, myFixture.editor, myFixture.file, null)
    myFixture.checkResult(after)
  }

  private fun inline(before: String, after: String) {
    myFixture.configureByText("a.sol", before)
    val target = myFixture.elementAtCaret
    val handler = SolInlineVariableHandler()
    assertTrue(handler.canInlineElement(target))
    handler.inlineElement(project, myFixture.editor, target)
    myFixture.checkResult(after)
  }

  fun testIntroduceFromSelection() = introduce(
    """
      contract A {
          function f(uint a, uint b) external pure returns (uint) {
              return <selection>a * b</selection> + 1;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a, uint b) external pure returns (uint) {
              uint256 <caret>value = a * b;
              return value + 1;
          }
      }
    """.trimIndent()
  )

  fun testIntroduceCallNamesTheVariable() = introduce(
    """
      contract A {
          function getTotal() public pure returns (uint) { return 1; }
          function f() external pure returns (bool) {
              return getTo<caret>tal() > 3;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function getTotal() public pure returns (uint) { return 1; }
          function f() external pure returns (bool) {
              uint256 <caret>total = getTotal();
              return total > 3;
          }
      }
    """.trimIndent()
  )

  fun testIntroduceStorageAndMemoryLocations() {
    introduce(
      """
        contract A {
            struct S { uint x; }
            S s;
            function f() external view returns (uint) {
                return <selection>s</selection>.x;
            }
        }
      """.trimIndent(),
      """
        contract A {
            struct S { uint x; }
            S s;
            function f() external view returns (uint) {
                S storage <caret>value = s;
                return value.x;
            }
        }
      """.trimIndent()
    )
    introduce(
      """
        contract A {
            function f(string memory a) external pure returns (uint) {
                return bytes(<selection>a</selection>).length;
            }
        }
      """.trimIndent(),
      """
        contract A {
            function f(string memory a) external pure returns (uint) {
                string memory <caret>value = a;
                return bytes(value).length;
            }
        }
      """.trimIndent()
    )
  }

  fun testIntroduceRefusesUnknownTypes() {
    myFixture.configureByText("a.sol", "contract A {\n    function f() external {\n        <selection>unknownThing</selection> + 1;\n    }\n}")
    assertThrows(CommonRefactoringUtil.RefactoringErrorHintException::class.java) {
      SolIntroduceVariableHandler().invoke(project, myFixture.editor, myFixture.file, null)
    }
  }

  fun testInline() = inline(
    """
      contract A {
          function f(uint a, uint b) external pure returns (uint) {
              uint s<caret>um = a + b;
              return sum * sum;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a, uint b) external pure returns (uint) {
              return (a + b) * (a + b);
          }
      }
    """.trimIndent()
  )

  fun testInlineFromUsage() = inline(
    """
      contract A {
          function f(uint[] memory xs) external pure returns (uint) {
              uint n = xs.length;
              return n<caret>;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint[] memory xs) external pure returns (uint) {
              return xs.length;
          }
      }
    """.trimIndent()
  )

  fun testInlineRefusesReassignedVariables() {
    myFixture.configureByText("a.sol", "contract A {\n    function f() external pure returns (uint) {\n        uint x<caret> = 1;\n        x = 2;\n        return x;\n    }\n}")
    assertThrows(CommonRefactoringUtil.RefactoringErrorHintException::class.java) {
      SolInlineVariableHandler().inlineElement(project, myFixture.editor, myFixture.elementAtCaret)
    }
  }
}
