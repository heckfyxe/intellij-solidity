package me.serce.solidity.ide.inspections

class RemovedLanguageFeatureInspectionTest : SolInspectionsTestBase(RemovedLanguageFeatureInspection()) {

  fun testNothingReportedWithoutPragma() = checkByText("""
      contract A {
          event E();
          function f() {
              var x = 1;
              uint t = now + 1 years;
              E();
              throw;
          }
      }
  """)

  fun testNothingReportedForOldPragma() = checkByText("""
      pragma solidity ^0.4.24;
      contract A {
          function A() public {}
          function f() {
              var x = 1;
              bytes32 h = sha3("a");
              uint t = now + 1 years;
              throw;
          }
      }
  """)

  fun testNothingReportedForRangeIncludingOldVersions() = checkByText("""
      pragma solidity >=0.4.22 <0.9.0;
      contract A {
          function f() public {
              throw;
          }
      }
  """)

  fun testRemovedIn050() = checkByText("""
      pragma solidity ^0.5.0;
      contract A {
          event E(uint a);
          function f(address a) public {
              /*@error descr="'var' was removed in Solidity 0.5.0, declare the type explicitly"@*/var/*@/error@*/ x = 1;
              uint t = 1 /*@error descr="The 'years' unit was removed in Solidity 0.5.0"@*/years/*@/error@*/;
              bytes32 h = /*@error descr="'sha3' was removed in Solidity 0.5.0, use 'keccak256'"@*/sha3/*@/error@*/("a");
              uint g = /*@error descr="'msg.gas' was removed in Solidity 0.5.0, use 'gasleft()'"@*/msg.gas/*@/error@*/;
              bytes32 b = /*@error descr="'block.blockhash' was removed in Solidity 0.5.0, use 'blockhash'"@*/block.blockhash/*@/error@*/(1);
              int y = /*@error descr="Unary '+' was removed in Solidity 0.5.0"@*/+/*@/error@*/1;
              uint bal = /*@error descr="Contract types have no address members since Solidity 0.5.0, use 'address(this).balance'"@*/this.balance/*@/error@*/;
              /*@error descr="Events must be emitted with 'emit' since Solidity 0.5.0"@*/E(1)/*@/error@*/;
              /*@error descr="'throw' was removed in Solidity 0.5.0, use 'revert()', 'require()' or 'assert()'"@*/throw/*@/error@*/;
          }
          function kill(address payable a) public {
              /*@error descr="'suicide' was removed in Solidity 0.5.0, use 'selfdestruct'"@*/suicide/*@/error@*/(a);
          }
      }
  """)

  fun testFunctionVisibilityAndOldConstructor() = checkByText("""
      pragma solidity 0.5.17;
      contract A {
          function /*@error descr="Functions can't have the same name as the contract since Solidity 0.5.0, use 'constructor(...)' to define a constructor"@*/A/*@/error@*/() public {}
          function /*@error descr="Function visibility must be specified explicitly since Solidity 0.5.0"@*/f/*@/error@*/() {}
          function g() internal {}
      }
  """)

  fun testRemovedIn070() = checkByText("""
      pragma solidity >=0.7.0 <0.9.0;
      contract A {
          function f(address payable a) public {
              uint t = /*@error descr="'now' was removed in Solidity 0.7.0, use 'block.timestamp'"@*/now/*@/error@*/;
              uint s = 1 /*@error descr="The 'szabo' denomination was removed in Solidity 0.7.0"@*/szabo/*@/error@*/;
              uint fi = 1 /*@error descr="The 'finney' denomination was removed in Solidity 0.7.0"@*/finney/*@/error@*/;
              a.call./*@error descr="The '.value(...)'/'.gas(...)' call syntax was removed in Solidity 0.7.0, use call options '{value: ..., gas: ...}'"@*/value/*@/error@*/(1)("");
          }
      }
  """)

  fun testRemovedIn070NotReportedFor06() = checkByText("""
      pragma solidity ^0.6.0;
      contract A {
          function f(address payable a) public {
              uint t = now;
              uint s = 1 szabo;
              a.call.value(1)("");
          }
      }
  """)

  fun testByteRemovedIn080() = checkByText("""
      pragma solidity ^0.8.0;
      contract A {
          /*@error descr="The type 'byte' was removed in Solidity 0.8.0, use 'bytes1'"@*/byte/*@/error@*/ b;
          function f() public pure returns (uint r) {
              assembly { r := byte(0, 1) }
          }
      }
  """)

  fun testLogFunctionsRemovedIn080() = checkByText("""
      pragma solidity ^0.8.0;
      contract A {
          function f(bytes32 t) public {
              /*@error descr="The global function 'log0' was removed in Solidity 0.8.0, use events or inline assembly"@*/log0/*@/error@*/(t);
              /*@error descr="The global function 'log2' was removed in Solidity 0.8.0, use events or inline assembly"@*/log2/*@/error@*/(t, t, t);
              assembly { log1(0, 0, t) }
          }
      }
      contract B {
          function log1(bytes32 a) internal {}
          function g(bytes32 t) public { log1(t); }
      }
  """)

  fun testLogFunctionsAllowedIn07() = checkByText("""
      pragma solidity ^0.7.0;
      contract A {
          function f(bytes32 t) public { log0(t); }
      }
  """)

  fun testByteAllowedIn07() = checkByText("""
      pragma solidity ^0.7.0;
      contract A {
          byte b;
      }
  """)

  fun testModernCodeIsClean() = checkByText("""
      // SPDX-License-Identifier: MIT
      pragma solidity ^0.8.20;

      function freeFn(uint a) pure returns (uint) { return a; }

      interface IERC20 {
          function transfer(address to, uint256 value) external returns (bool);
      }

      contract Base {
          uint public balance;
          function now() internal view returns (uint) { return block.timestamp; }
      }

      contract A is Base {
          event Transfer(address indexed from, address indexed to, uint value);
          error Failed();
          bytes1 b;

          constructor() {}

          function sha3(bytes memory data) internal pure returns (bytes32) { return keccak256(data); }

          function f(address payable a, IERC20 token) external payable {
              uint t = block.timestamp + 1 weeks + now();
              uint v = 1 ether + 1 gwei + 1 wei;
              uint g = gasleft();
              bytes32 h = blockhash(block.number - 1);
              bytes32 k = sha3("a");
              uint bal = this.balance + address(this).balance;
              int y = -1;
              (bool ok, ) = a.call{value: msg.value, gas: 5000}("");
              token.transfer(a, 1);
              emit Transfer(msg.sender, a, v);
              if (!ok) revert Failed();
              unchecked { t++; }
          }

          receive() external payable {}
          fallback() external payable {}
      }
  """)

  fun testThrowFix() = checkFixByText("Replace with 'revert()'",
    """
      pragma solidity ^0.5.0;
      contract A {
          function f() public {
              <error descr="'throw' was removed in Solidity 0.5.0, use 'revert()', 'require()' or 'assert()'">th<caret>row</error>;
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.5.0;
      contract A {
          function f() public {
              revert();
          }
      }
    """.trimIndent())

  fun testNowFix() = checkFixByText("Replace with 'block.timestamp'",
    """
      pragma solidity ^0.7.0;
      contract A {
          function f() public view returns (uint) {
              return <error descr="'now' was removed in Solidity 0.7.0, use 'block.timestamp'">n<caret>ow</error>;
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.7.0;
      contract A {
          function f() public view returns (uint) {
              return block.timestamp;
          }
      }
    """.trimIndent())

  fun testSha3Fix() = checkFixByText("Replace with 'keccak256'",
    """
      pragma solidity ^0.5.0;
      contract A {
          function f() public pure returns (bytes32) {
              return <error descr="'sha3' was removed in Solidity 0.5.0, use 'keccak256'">sh<caret>a3</error>("a");
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.5.0;
      contract A {
          function f() public pure returns (bytes32) {
              return keccak256("a");
          }
      }
    """.trimIndent())

  fun testCallOptionsFix() = checkFixByText("Convert to call options",
    """
      pragma solidity ^0.7.0;
      contract A {
          function f(address payable a) public {
              a.call.<error descr="The '.value(...)'/'.gas(...)' call syntax was removed in Solidity 0.7.0, use call options '{value: ..., gas: ...}'">ga<caret>s</error>(5000).value(1 ether)("");
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.7.0;
      contract A {
          function f(address payable a) public {
              a.call{gas: 5000, value: 1 ether}("");
          }
      }
    """.trimIndent())

  fun testByteFix() = checkFixByText("Replace with 'bytes1'",
    """
      pragma solidity ^0.8.0;
      contract A {
          <error descr="The type 'byte' was removed in Solidity 0.8.0, use 'bytes1'">by<caret>te</error> b;
      }
    """.trimIndent(),
    """
      pragma solidity ^0.8.0;
      contract A {
          bytes1 b;
      }
    """.trimIndent())

  fun testEmitFix() = checkFixByText("Add 'emit'",
    """
      pragma solidity ^0.5.0;
      contract A {
          event E(uint a);
          function f() public {
              <error descr="Events must be emitted with 'emit' since Solidity 0.5.0"><caret>E(1)</error>;
          }
      }
    """.trimIndent(),
    """
      pragma solidity ^0.5.0;
      contract A {
          event E(uint a);
          function f() public {
              emit E(1);
          }
      }
    """.trimIndent())
}
