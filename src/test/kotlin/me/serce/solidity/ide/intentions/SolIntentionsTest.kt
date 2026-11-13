package me.serce.solidity.ide.intentions

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import me.serce.solidity.utils.SolTestBase

class SolIntentionsTest : SolTestBase() {
  private fun doIntention(name: String, before: String, after: String) {
    myFixture.configureByText("a.sol", before)
    myFixture.launchAction(myFixture.findSingleIntention(name))
    myFixture.checkResult(after)
  }

  private fun assertUnavailable(name: String, code: String) {
    myFixture.configureByText("a.sol", code)
    assertEmpty(myFixture.filterAvailableIntentions(name))
  }

  fun testAddNatSpecToFunction() = doIntention(
    "Add NatSpec documentation",
    """
      contract A {
          function tran<caret>sfer(address to, uint256 amount) external returns (bool ok, uint256) {}
      }
    """.trimIndent(),
    """
      contract A {
          /// @notice <caret>
          /// @param to 
          /// @param amount 
          /// @return ok 
          /// @return 
          function transfer(address to, uint256 amount) external returns (bool ok, uint256) {}
      }
    """.trimIndent()
  )

  fun testAddNatSpecToContractAndEvent() {
    doIntention("Add NatSpec documentation", "contract To<caret>ken {}", "/// @title Token\n/// @notice <caret>\ncontract Token {}")
    doIntention(
      "Add NatSpec documentation",
      "contract A {\n    event Tran<caret>sfer(address indexed from, uint256 value);\n}",
      "contract A {\n    /// @notice <caret>\n    /// @param from \n    /// @param value \n    event Transfer(address indexed from, uint256 value);\n}"
    )
  }

  fun testNoNatSpecWhenDocumented() =
    assertUnavailable("Add NatSpec documentation", "contract A {\n    /// @notice x\n    function f<caret>() external {}\n}")

  fun testNoNatSpecInBody() {
    assertUnavailable("Add NatSpec documentation", "contract A {\n    function f() external { uint <caret>x; }\n}")
  }

  fun testDigitSeparators() {
    doIntention("Add digit separators", "contract A { uint x = 1000<caret>000; }", "contract A { uint x = 1_000_000; }")
    doIntention("Remove digit separators", "contract A { uint x = 1_000<caret>_000; }", "contract A { uint x = 1000000; }")
    assertUnavailable("Add digit separators", "contract A { uint x = 10<caret>00; }")
    assertUnavailable("Add digit separators", "contract A { uint x = 0x1000<caret>00; }")
  }

  fun testWrapInUnchecked() = doIntention(
    "Wrap in 'unchecked' block",
    """
      contract A {
          function f(uint i) external {
              i<caret>++;
          }
      }
    """.trimIndent(),
    """
      contract A {
          function f(uint i) external {
              unchecked {
                  <caret>i++;
              }
          }
      }
    """.trimIndent()
  )

  fun testNoUncheckedInsideUnchecked() =
    assertUnavailable("Wrap in 'unchecked' block", "contract A {\n    function f(uint i) external {\n        unchecked { i<caret>++; }\n    }\n}")

  private fun doPostfix(before: String, after: String) {
    TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    myFixture.configureByText("a.sol", before)
    myFixture.type('\t')
    myFixture.checkResult(after)
  }

  fun testPostfixIf() = doPostfix(
    "contract A {\n    function f(uint x) external {\n        x > 0.if<caret>\n    }\n}",
    "contract A {\n    function f(uint x) external {\n        if (x > 0) {\n            <caret>\n        }\n    }\n}"
  )

  fun testPostfixRequire() = doPostfix(
    "contract A {\n    function f(uint x) external {\n        x > 0.require<caret>\n    }\n}",
    "contract A {\n    function f(uint x) external {\n        require(x > 0, \"<caret>\");\n    }\n}"
  )

  fun testPostfixNot() = doPostfix(
    "contract A {\n    function f(bool b) external returns (bool) {\n        return b.not<caret>;\n    }\n}",
    "contract A {\n    function f(bool b) external returns (bool) {\n        return !b<caret>;\n    }\n}"
  )

  fun testPostfixReturn() = doPostfix(
    "contract A {\n    function f(uint x) external returns (uint) {\n        x + 1.return<caret>\n    }\n}",
    "contract A {\n    function f(uint x) external returns (uint) {\n        return x + 1;<caret>\n    }\n}"
  )
}
