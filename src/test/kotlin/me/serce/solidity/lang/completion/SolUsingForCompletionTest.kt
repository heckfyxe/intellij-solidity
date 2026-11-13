package me.serce.solidity.lang.completion

import org.intellij.lang.annotations.Language

class SolUsingForCompletionTest : SolCompletionTestBase() {
  fun testContractLevelUsingInSameContract() = checkCompletion(hashSetOf("twice"), """
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    contract A {
        using L for uint256;
        function f(uint256 x) public pure {
            x./*caret*/
        }
    }
  """)

  fun testContractLevelUsingNotInOtherContract() = checkNoCompletion("twice", """
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    contract A {
        using L for uint256;
    }
    contract B {
        function f(uint256 x) public pure {
            x./*caret*/
        }
    }
  """)

  fun testContractLevelUsingNotInDerivedContract() = checkNoCompletion("twice", """
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    contract Base {
        using L for uint256;
    }
    contract Derived is Base {
        function f(uint256 x) public pure {
            x./*caret*/
        }
    }
  """)

  fun testOperatorBindingNotOffered() = checkNoCompletion("add", """
    pragma solidity ^0.8.26;
    type Fixed is int256;
    function add(Fixed a, Fixed b) pure returns (Fixed) { return a; }
    function neg(Fixed a) pure returns (Fixed) { return a; }
    using {add as +, neg} for Fixed global;
    function free(Fixed x) pure {
        x./*caret*/
    }
  """, present = "neg")

  fun testImporterDoesNotSeeFileLevelUsing() {
    InlineFile("""
      pragma solidity ^0.8.26;
      library L {
          function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
      }
      using L for uint256;
    """, name = "a.sol")
    checkNoCompletion("twice", """
      pragma solidity ^0.8.26;
      import "./a.sol";
      contract B {
          function f(uint256 x) public pure {
              x./*caret*/
          }
      }
    """)
  }

  fun testGlobalUsingFromAnotherFile() {
    InlineFile("""
      pragma solidity ^0.8.26;
      type Fixed is int256;
      function neg(Fixed a) pure returns (Fixed) { return a; }
      using {neg} for Fixed global;
    """, name = "a.sol")
    checkCompletion(hashSetOf("neg"), """
      pragma solidity ^0.8.26;
      import {Fixed} from "./a.sol";
      contract B {
          function f(Fixed x) public pure {
              x./*caret*/
          }
      }
    """)
  }

  private fun checkNoCompletion(absent: String, @Language("Solidity") code: String, present: String? = null) {
    InlineFile(code).withCaret()
    val variants = myFixture.completeBasic()
    val completions = variants?.map { it.lookupString }.orEmpty()
    assertFalse("$completions shouldn't contain $absent", absent in completions)
    if (present != null) {
      assertTrue("$completions should contain $present", present in completions)
    }
  }
}
