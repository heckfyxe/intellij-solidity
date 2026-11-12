package me.serce.solidity.ide.typing

import me.serce.solidity.utils.SolTestBase

class SolBraceTypingTest : SolTestBase() {
  fun testParenIsClosedBeforeSemicolon() = checkByText(
    "contract A { function f() public { g<caret>; } }",
    "contract A { function f() public { g(<caret>); } }"
  ) { myFixture.type('(') }

  fun testParenIsNotClosedBeforeIdentifier() = checkByText(
    "contract A { function f() public { uint x = <caret>a + b; } }",
    "contract A { function f() public { uint x = (<caret>a + b; } }"
  ) { myFixture.type('(') }
}
