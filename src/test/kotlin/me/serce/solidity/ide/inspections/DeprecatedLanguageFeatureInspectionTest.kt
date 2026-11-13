package me.serce.solidity.ide.inspections

class DeprecatedLanguageFeatureInspectionTest : SolInspectionsTestBase(DeprecatedLanguageFeatureInspection()) {

  fun testNothingReportedWithoutPragma() = checkByText("""
      contract A {
          function f(address payable a) public {
              uint d = block.difficulty;
              a.transfer(1);
              selfdestruct(a);
          }
      }
  """)

  fun testNothingReportedBefore0818() = checkByText("""
      pragma solidity ^0.8.0;
      contract A {
          function f(address payable a) public {
              uint d = block.difficulty;
              a.transfer(1);
              selfdestruct(a);
          }
      }
  """)

  fun testDeprecatedIn0818() = checkByText("""
      pragma solidity ^0.8.18;
      contract A {
          modifier m() virtual { _; }
          function f(address payable a) public {
              uint d = block./*@warning descr="'block.difficulty' is deprecated since Solidity 0.8.18, use 'block.prevrandao'"@*/difficulty/*@/warning@*/;
              a.transfer(1);
              /*@warning descr="'selfdestruct' is deprecated since Solidity 0.8.18 (EIP-6049); since EIP-6780 it no longer deletes the contract code"@*/selfdestruct/*@/warning@*/(a);
          }
      }
  """)

  fun testDeprecatedIn0831() = checkByText("""
      pragma solidity ^0.8.31;
      /*@warning descr="ABI coder v1 is deprecated since Solidity 0.8.31"@*/pragma abicoder v1;/*@/warning@*/
      interface IERC20 {
          function transfer(address to, uint256 value) external returns (bool);
      }
      abstract contract A {
          modifier m() /*@warning descr="Virtual modifiers are deprecated since Solidity 0.8.31"@*/virtual/*@/warning@*/ { _; }
          function f(address payable a, IERC20 token) public {
              a./*@warning descr="'transfer' on addresses is deprecated since Solidity 0.8.31, use 'call{value: ...}(\"\")'"@*/transfer/*@/warning@*/(1);
              bool ok = payable(msg.sender)./*@warning descr="'send' on addresses is deprecated since Solidity 0.8.31, use 'call{value: ...}(\"\")'"@*/send/*@/warning@*/(1);
              token.transfer(a, 1);
          }
      }
  """)

  fun testUserDefinedNamesAreNotReported() = checkByText("""
      pragma solidity ^0.8.31;
      pragma abicoder v2;
      contract A {
          struct Block { uint difficulty; }
          function selfdestruct(address a) internal {}
          function f(address payable a) public {
              Block memory block;
              uint d = block.difficulty;
              selfdestruct(a);
              uint r = block.prevrandao;
          }
      }
  """)

  fun testContractComparison() = checkByText("""
      pragma solidity ^0.8.31;
      interface IERC20 {}
      contract A {
          IERC20 token;
          address owner;
          function f(IERC20 other, A a) public view returns (bool) {
              bool x = /*@warning descr="Comparison of variables of contract type is deprecated since Solidity 0.8.31, compare their addresses"@*/token == other/*@/warning@*/;
              bool y = /*@warning descr="Comparison of variables of contract type is deprecated since Solidity 0.8.31, compare their addresses"@*/a != this/*@/warning@*/;
              bool z = address(token) == address(other);
              bool w = owner == msg.sender;
              return x && y && z && w;
          }
      }
  """)

  fun testContractComparisonNotReportedBefore0831() = checkByText("""
      pragma solidity ^0.8.20;
      interface IERC20 {}
      contract A {
          function f(IERC20 a, IERC20 b) public pure returns (bool) { return a == b; }
      }
  """)

  fun testContractComparisonFix() = checkFixByText("Compare addresses",
    """
      pragma solidity ^0.8.31;
      interface IERC20 {}
      contract A {
          function f(IERC20 a, IERC20 b) public pure returns (bool) {
              return <warning descr="Comparison of variables of contract type is deprecated since Solidity 0.8.31, compare their addresses">a =<caret>= b</warning>;
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.8.31;
      interface IERC20 {}
      contract A {
          function f(IERC20 a, IERC20 b) public pure returns (bool) {
              return address(a) == address(b);
          }
      }
    """.trimIndent())

  fun testMemorySafeAssemblyComment() = checkByText("""
      pragma solidity ^0.8.31;
      contract A {
          function f() public pure returns (uint r) {
              /// /*@warning descr="The '@solidity memory-safe-assembly' comment is deprecated since Solidity 0.8.31, use 'assembly (\"memory-safe\")'"@*/@solidity/*@/warning@*/ memory-safe-assembly
              assembly { r := 1 }
              assembly ("memory-safe") { r := 2 }
              /// @dev regular docs
              assembly { r := 3 }
          }
      }
  """)

  fun testMemorySafeAssemblyNotReportedBefore0831() = checkByText("""
      pragma solidity ^0.8.20;
      contract A {
          function f() public pure returns (uint r) {
              /// @solidity memory-safe-assembly
              assembly { r := 1 }
          }
      }
  """)

  fun testMemorySafeAssemblyFix() = checkFixByText("Replace with 'assembly (\"memory-safe\")'",
    """
      pragma solidity ^0.8.31;
      contract A {
          function f() public pure returns (uint r) {
              /// <warning descr="The '@solidity memory-safe-assembly' comment is deprecated since Solidity 0.8.31, use 'assembly (\"memory-safe\")'">@soli<caret>dity</warning> memory-safe-assembly
              assembly {
                  r := 1
              }
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.8.31;
      contract A {
          function f() public pure returns (uint r) {
              assembly ("memory-safe") {
                  r := 1
              }
          }
      }
    """.trimIndent())

  fun testMemorySafeAssemblyFixWithDialect() = checkFixByText("Replace with 'assembly (\"memory-safe\")'",
    """
      pragma solidity ^0.8.31;
      contract A {
          function f() public pure returns (uint r) {
              /** <warning descr="The '@solidity memory-safe-assembly' comment is deprecated since Solidity 0.8.31, use 'assembly (\"memory-safe\")'">@soli<caret>dity</warning> memory-safe-assembly */
              assembly "evmasm" { r := 1 }
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.8.31;
      contract A {
          function f() public pure returns (uint r) {
              assembly "evmasm" ("memory-safe") { r := 1 }
          }
      }
    """.trimIndent())

  fun testPrevrandaoFix() = checkFixByText("Replace with 'prevrandao'",
    """
      pragma solidity ^0.8.20;
      contract A {
          function f() public view returns (uint) {
              return block.<warning descr="'block.difficulty' is deprecated since Solidity 0.8.18, use 'block.prevrandao'">diffi<caret>culty</warning>;
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.8.20;
      contract A {
          function f() public view returns (uint) {
              return block.prevrandao;
          }
      }
    """.trimIndent())
}
