package me.serce.solidity.ide.typing

import me.serce.solidity.utils.SolTestBase

class SolQuoteTypingTest : SolTestBase() {
  fun testDoubleQuoteIsClosed() = checkByText(
    "contract A { string s = <caret> }",
    "contract A { string s = \"<caret>\" }"
  ) { myFixture.type('"') }

  fun testSingleQuoteIsClosed() = checkByText(
    "contract A { string s = <caret> }",
    "contract A { string s = '<caret>' }"
  ) { myFixture.type('\'') }

  fun testTypingClosingQuoteStepsOverIt() = checkByText(
    "contract A { string s = \"abc<caret>\"; }",
    "contract A { string s = \"abc\"<caret>; }"
  ) { myFixture.type('"') }

  fun testUnterminatedStringIsAnError() {
    myFixture.configureByText("A.sol", "contract A { string s = \"abc\n; string t = \"a\\\"b\"; }")
    val errors = myFixture.doHighlighting().filter { it.description == "Missing closing quote" }
    assertEquals(listOf("\"abc"), errors.map { it.text })
  }
}
