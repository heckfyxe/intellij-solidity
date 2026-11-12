package me.serce.solidity.ide.typing

import me.serce.solidity.utils.SolTestBase

class SolLineDocCommentEnterHandlerTest : SolTestBase() {
  private fun doTest(before: String, after: String) = checkByText(before.trimIndent(), after.trimIndent()) {
    myFixture.type('\n')
  }

  fun testContinuesAtEndOfLine() = doTest(
    """
      contract A {
          /// @notice Does things<caret>
          function f() public {}
      }
    """,
    """
      contract A {
          /// @notice Does things
          /// <caret>
          function f() public {}
      }
    """
  )

  fun testSplitsLineInTheMiddle() = doTest(
    """
      /// Does <caret>things
      contract A {}
    """,
    """
      /// Does 
      /// <caret>things
      contract A {}
    """
  )

  fun testContinuesEmptyDocLine() = doTest(
    """
      ///<caret>
      contract A {}
    """,
    """
      ///
      /// <caret>
      contract A {}
    """
  )

  fun testPlainLineCommentIsNotContinued() = doTest(
    """
      contract A {
          // a comment<caret>
      }
    """,
    """
      contract A {
          // a comment
          <caret>
      }
    """
  )

  fun testFourSlashesAreNotContinued() = doTest(
    """
      //// a comment<caret>
      contract A {}
    """,
    """
      //// a comment
      <caret>
      contract A {}
    """
  )

  fun testCaretBeforeSlashesIsNotContinued() = doTest(
    """
      contract A {
          <caret>/// doc
          function f() public {}
      }
    """,
    """
      contract A {
          
          <caret>/// doc
          function f() public {}
      }
    """
  )

  fun testDocInsideBlockCommentIsNotContinued() = doTest(
    """
      /*
      /// not a doc<caret>
      */
      contract A {}
    """,
    """
      /*
      /// not a doc
      <caret>
      */
      contract A {}
    """
  )

  fun testDocCommentBlockIsContinued() = doTest(
    """
      contract A {
          /**<caret>
          function f() public {}
      }
    """,
    """
      contract A {
          /**
           * <caret>
           */
          function f() public {}
      }
    """
  )
}
