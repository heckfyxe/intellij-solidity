package me.serce.solidity.lang.core.resolve

import me.serce.solidity.lang.psi.SolNamedElement

/** Which `using ... for` declarations apply where, see https://docs.soliditylang.org/en/latest/contracts.html#using-for */
class SolUsingForScopeResolveTest : SolResolveTestBase() {
  fun testContractLevelUsingAppliesInsideContract() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
                //x
    }
    contract A {
        using L for uint256;
        function f(uint256 x) public pure returns (uint256) {
            return x.twice();
                    //^
        }
    }
  """)

  fun testContractLevelUsingNotSeenFromOtherContractInFile() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    contract A {
        using L for uint256;
    }
    contract B {
        function f(uint256 x) public pure returns (uint256) {
            return x.twice();
                    //^unresolved
        }
    }
  """)

  fun testContractLevelUsingNotInheritedSince070() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    contract Base {
        using L for uint256;
    }
    contract Derived is Base {
        function f(uint256 x) public pure returns (uint256) {
            return x.twice();
                    //^unresolved
        }
    }
  """)

  fun testContractLevelUsingInheritedBefore070() = checkByCode("""
    pragma solidity ^0.6.0;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
                //x
    }
    contract Base {
        using L for uint256;
    }
    contract Derived is Base {
        function f(uint256 x) public pure returns (uint256) {
            return x.twice();
                    //^
        }
    }
  """)

  fun testFileLevelUsingAppliesToFreeFunctionsAndContracts() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
                //x
    }
    using L for uint256;
    function free(uint256 x) pure returns (uint256) {
        return x.twice();
                //^
    }
  """)

  fun testUsingWithTypeMismatchDoesNotApply() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
    }
    using L for uint256;
    function free(bool x) pure returns (uint256) {
        return x.twice();
                //^unresolved
    }
  """)

  fun testWildcardUsing() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
                //x
    }
    contract A {
        using L for *;
        function f(uint256 x) public pure returns (uint256) {
            return x.twice();
                    //^
        }
    }
  """)

  fun testListedFunctionsOnly() = checkByCode("""
    pragma solidity ^0.8.26;
    library L {
        function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
        function thrice(uint256 a) internal pure returns (uint256) { return a * 3; }
    }
    using {L.twice} for uint256;
    function free(uint256 x) pure returns (uint256) {
        return x.thrice();
                //^unresolved
    }
  """)

  fun testListedFreeFunction() = checkByCode("""
    pragma solidity ^0.8.26;
    function twice(uint256 a) pure returns (uint256) { return a * 2; }
            //x
    using {twice} for uint256;
    function free(uint256 x) pure returns (uint256) {
        return x.twice();
                //^
    }
  """)

  fun testOperatorBindingIsNotMember() = checkByCode("""
    pragma solidity ^0.8.26;
    type Fixed is int256;
    function add(Fixed a, Fixed b) pure returns (Fixed) { return Fixed.wrap(Fixed.unwrap(a) + Fixed.unwrap(b)); }
    using {add as +} for Fixed global;
    function free(Fixed x) pure returns (Fixed) {
        return x.add(x);
                //^unresolved
    }
  """)

  fun testOperatorAndMemberBinding() = checkByCode("""
    pragma solidity ^0.8.26;
    type Fixed is int256;
    function add(Fixed a, Fixed b) pure returns (Fixed) { return Fixed.wrap(Fixed.unwrap(a) + Fixed.unwrap(b)); }
            //x
    using {add as +, add} for Fixed global;
    function free(Fixed x) pure returns (Fixed) {
        return x.add(x);
                //^
    }
  """)

  fun testImporterDoesNotSeeFileLevelUsing() {
    InlineFile("""
      pragma solidity ^0.8.26;
      library L {
          function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
      }
      using L for uint256;
    """, name = "a.sol")
    InlineFile("""
      pragma solidity ^0.8.26;
      import "./a.sol";
      contract B {
          function f(uint256 x) public pure returns (uint256) {
              return x.twice();
                      //^
          }
      }
    """)
    assertUnresolvedInEditor()
  }

  fun testImporterDoesNotSeeContractLevelUsing() {
    InlineFile("""
      pragma solidity ^0.8.26;
      library L {
          function twice(uint256 a) internal pure returns (uint256) { return a * 2; }
      }
      contract A {
          using L for uint256;
      }
    """, name = "a.sol")
    InlineFile("""
      pragma solidity ^0.8.26;
      import "./a.sol";
      contract B is A {
          function f(uint256 x) public pure returns (uint256) {
              return x.twice();
                      //^
          }
      }
    """)
    assertUnresolvedInEditor()
  }

  fun testGlobalUsingSeenFromAnotherFile() = testResolveBetweenFiles(
    InlineFile("""
      pragma solidity ^0.8.26;
      type Fixed is int256;
      function neg(Fixed a) pure returns (Fixed) { return Fixed.wrap(-Fixed.unwrap(a)); }
              //x
      using {neg} for Fixed global;
    """, name = "a.sol"),
    InlineFile("""
      pragma solidity ^0.8.26;
      import {Fixed} from "./a.sol";
      contract B {
          function f(Fixed x) public pure returns (Fixed) {
              return x.neg();
                      //^
          }
      }
    """)
  )

  fun testNonGlobalUsingForUserTypeNotSeenFromAnotherFile() {
    InlineFile("""
      pragma solidity ^0.8.26;
      type Fixed is int256;
      function neg(Fixed a) pure returns (Fixed) { return Fixed.wrap(-Fixed.unwrap(a)); }
      using {neg} for Fixed;
    """, name = "a.sol")
    InlineFile("""
      pragma solidity ^0.8.26;
      import {Fixed} from "./a.sol";
      contract B {
          function f(Fixed x) public pure returns (Fixed) {
              return x.neg();
                      //^
          }
      }
    """)
    assertUnresolvedInEditor()
  }

  private fun assertUnresolvedInEditor() {
    val (refElement, _) = findElementAndDataInEditor<SolNamedElement>("^")
    val resolved = refElement.reference?.resolve()
    assertNull("`${refElement.text}` should be unresolved, was resolved to ${resolved?.text}", resolved)
  }
}
