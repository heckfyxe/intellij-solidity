package me.serce.solidity.ide.refactoring

import com.intellij.refactoring.util.CommonRefactoringUtil
import me.serce.solidity.utils.SolTestBase

class SolExtractFunctionTest : SolTestBase() {
  private fun extract(before: String, after: String) {
    myFixture.configureByText("a.sol", before)
    SolExtractFunctionHandler().invoke(project, myFixture.editor, myFixture.file, null)
    myFixture.checkResult(after)
  }

  private fun refused(code: String, message: String) {
    myFixture.configureByText("a.sol", code)
    try {
      SolExtractFunctionHandler().invoke(project, myFixture.editor, myFixture.file, null)
      fail("expected a refusal")
    } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
      assertEquals(message, e.message)
    }
  }

  fun testStatementsWithoutValues() = extract(
    """
      contract A {
          uint total;
          event Done(uint);

          function f(uint a) external {
              <selection>total += a;
              emit Done(total);</selection>
          }
      }
    """.trimIndent(),
    """
      contract A {
          uint total;
          event Done(uint);

          function f(uint a) external {
              <caret>extracted(a);
          }

          function extracted(uint a) private {
              total += a;
              emit Done(total);
          }
      }
    """.trimIndent()
  )

  fun testDeclaredVariableUsedAfterIsReturned() = extract(
    """
      contract A {
          function f(uint a, uint[] memory xs) external pure returns (uint) {
              <selection>uint sum = a;
              for (uint i = 0; i < xs.length; i++) {
                  sum += xs[i];
              }</selection>
              return sum * 2;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a, uint[] memory xs) external pure returns (uint) {
              uint sum = <caret>extracted(a, xs);
              return sum * 2;
          }

          function extracted(uint a, uint[] memory xs) private pure returns (uint) {
              uint sum = a;
              for (uint i = 0; i < xs.length; i++) {
                  sum += xs[i];
              }
              return sum;
          }
      }
    """.trimIndent()
  )

  fun testAssignedOuterVariablesAreReturned() = extract(
    """
      contract A {
          function f(uint a) external view returns (uint r) {
              uint b = 1;
              <selection>uint c = a + b;
              b = c * 2;
              r = b + c;</selection>
              b++;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a) external view returns (uint r) {
              uint b = 1;
              (b, r) = <caret>extracted(a, b);
              b++;
          }

          function extracted(uint a, uint b) private view returns (uint, uint) {
              uint r;
              uint c = a + b;
              b = c * 2;
              r = b + c;
              return (b, r);
          }
      }
    """.trimIndent()
  )

  fun testStatementAtCaretInUncheckedBlock() = extract(
    """
      contract A {
          function f(uint a) internal pure returns (uint) {
              uint b;
              unchecked {
                  b = a<caret> * 3;
              }
              return b;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a) internal pure returns (uint) {
              uint b;
              unchecked {
                  b = <caret>extracted(a);
              }
              return b;
          }

          function extracted(uint a) private pure returns (uint) {
              unchecked {
                  uint b;
                  b = a * 3;
                  return b;
              }
          }
      }
    """.trimIndent()
  )

  fun testNameDoesNotClash() = extract(
    """
      contract A {
          function extracted() public {}
          function f() public {
              <selection>extracted();</selection>
          }
      }
    """.trimIndent(),
    """
      contract A {
          function extracted() public {}
          function f() public {
              <caret>extracted2();
          }

          function extracted2() private {
              extracted();
          }
      }
    """.trimIndent()
  )

  fun testFreeFunctionHasNoVisibility() = extract(
    """
      function f(uint a) pure returns (uint) {
          <selection>uint b = a + 1;</selection>
          return b;
      }
    """.trimIndent(),
    """
      function f(uint a) pure returns (uint) {
          uint b = <caret>extracted(a);
          return b;
      }

      function extracted(uint a) pure returns (uint) {
          uint b = a + 1;
          return b;
      }
    """.trimIndent()
  )

  fun testReturnIsRefused() = refused(
    """
      contract A {
          function f(uint a) external pure returns (uint) {
              <selection>if (a > 1) return 2;</selection>
              return 1;
          }
      }
    """.trimIndent(),
    "The statements contain 'return'"
  )

  fun testBreakOutOfSelectionIsRefused() = refused(
    """
      contract A {
          function f(uint a) external pure {
              while (true) {
                  <selection>if (a > 1) break;</selection>
              }
          }
      }
    """.trimIndent(),
    "The statements contain 'break' or 'continue' for a loop around them"
  )

  fun testPartialStatementIsRefused() = refused(
    """
      contract A {
          function f(uint a) external pure returns (uint) {
              return <selection>a + 1</selection>;
          }
      }
    """.trimIndent(),
    "Select whole statements inside a function body"
  )

  fun testMixedDeclaredAndAssignedOutputs() = extract(
    """
      contract A {
          function f(uint a) external pure returns (uint) {
              uint b = a;
              <selection>uint c = b + 1;
              b += c;</selection>
              return b + c;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint a) external pure returns (uint) {
              uint b = a;
              uint c;
              (c, b) = <caret>extracted(b);
              return b + c;
          }

          function extracted(uint b) private pure returns (uint, uint) {
              uint c = b + 1;
              b += c;
              return (c, b);
          }
      }
    """.trimIndent()
  )
}
