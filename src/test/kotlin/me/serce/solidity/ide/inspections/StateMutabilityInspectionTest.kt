package me.serce.solidity.ide.inspections

class StateMutabilityInspectionTest : SolInspectionsTestBase(StateMutabilityInspection()) {
  private val viewModifies =
    "Function declared as view, but this expression (potentially) modifies the state and thus requires non-payable (the default) or payable"
  private val pureModifies =
    "Function declared as pure, but this expression (potentially) modifies the state and thus requires non-payable (the default) or payable"
  private val pureReads =
    "Function declared as pure, but this expression (potentially) reads from the environment or state and thus requires \\\"view\\\""

  private fun viewErr(code: String) = "/*@error descr=\"$viewModifies\"@*/$code/*@/error@*/"
  private fun pureModErr(code: String) = "/*@error descr=\"$pureModifies\"@*/$code/*@/error@*/"
  private fun pureReadErr(code: String) = "/*@error descr=\"$pureReads\"@*/$code/*@/error@*/"

  fun testViewAssignsStateVariable() = checkByText("""
    contract C {
        uint256 total;
        mapping(address => uint256) balances;
        struct S { uint256 a; uint256[] list; }
        S s;
        uint256[] arr;

        function f(uint256 x) public view {
            ${viewErr("total")} = x;
            ${viewErr("total")} += x;
            ${viewErr("balances[msg.sender]")} = x;
            ${viewErr("s.a")} = x;
            ${viewErr("s.list[0]")} = x;
            ${viewErr("total")}++;
            --${viewErr("total")};
            delete ${viewErr("arr")};
        }
    }
  """)

  fun testViewTupleAssignment() = checkByText("""
    contract C {
        uint256 a;
        function f() public view {
            uint256 b;
            (${viewErr("a")}, b) = (1, 2);
        }
    }
  """)

  fun testViewWritesThroughStoragePointer() = checkByText("""
    contract C {
        struct S { uint256 a; }
        mapping(uint256 => S) items;
        function f(uint256 id) public view {
            S storage item = items[id];
            ${viewErr("item.a")} = 1;
        }
    }
  """)

  fun testViewPushPopEmitNewSelfdestruct() = checkByText("""
    contract D {}
    contract C {
        uint256[] arr;
        event E(uint256 x);
        function f() public view {
            ${viewErr("arr.push(1)")};
            ${viewErr("arr.pop()")};
            ${viewErr("emit E(1)")};
            ${viewErr("new D()")};
            ${viewErr("selfdestruct(payable(msg.sender))")};
        }
    }
  """)

  fun testViewSendsEther() = checkByText("""
    contract C {
        function f(address payable to) public view {
            ${viewErr("to.transfer(1)")};
            ${viewErr("to.send(1)")};
            ${viewErr("to.call{value: 1}(\"\")")};
            ${viewErr("to.delegatecall(\"\")")};
            to.staticcall("");
        }
    }
  """)

  fun testViewCallsNonViewFunctions() = checkByText("""
    interface IERC20 {
        function transfer(address to, uint256 amount) external returns (bool);
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C {
        IERC20 token;
        uint256 x;
        function g() internal { x = 1; }
        function h() internal view returns (uint256) { return x; }
        function p() internal pure returns (uint256) { return 1; }
        function f() public view returns (uint256) {
            ${viewErr("g()")};
            ${viewErr("token.transfer(msg.sender, 1)")};
            return token.balanceOf(msg.sender) + h() + p();
        }
    }
  """)

  fun testPureReadsState() = checkByText("""
    contract C {
        uint256 total;
        uint256 constant MAX = 10;
        function v() public view returns (uint256) { return total; }
        function f() public pure returns (uint256 r) {
            r = ${pureReadErr("total")};
            r = MAX;
            r = ${pureReadErr("block.timestamp")};
            r = ${pureReadErr("block.number")};
            r = uint160(${pureReadErr("msg.sender")});
            r = uint160(${pureReadErr("tx.origin")});
            r = ${pureReadErr("gasleft()")};
            r = ${pureReadErr("v()")};
            r = ${pureReadErr("address(${pureReadErr("this")}).balance")} > 0 ? 1 : 0;
            bytes memory d = msg.data;
            bytes4 s = msg.sig;
        }
    }
  """)

  fun testPureModifiesState() = checkByText("""
    contract C {
        uint256 total;
        event E();
        function f() public pure {
            ${pureModErr("total")} = 1;
            ${pureModErr("emit E()")};
        }
    }
  """)

  fun testPureFunctionsWithoutStateAccess() = checkByText("""
    library SafeMath {
        function add(uint256 a, uint256 b) internal pure returns (uint256) {
            uint256 c = a + b;
            require(c >= a, "overflow");
            return c;
        }
    }
    contract C {
        using SafeMath for uint256;
        struct P { uint256 x; }
        error Bad(uint256 v);
        function f(uint256 a, bytes memory data) public pure returns (bytes32, uint256) {
            uint256[] memory arr = new uint256[](3);
            arr[0] = a.add(1);
            P memory p = P(1);
            p.x = 2;
            if (a == 0) revert Bad(a);
            (uint256 d) = abi.decode(data, (uint256));
            return (keccak256(abi.encodePacked(a, d)), type(uint256).max + arr.length + p.x);
        }
    }
  """)

  fun testViewFunctionsWithoutStateModification() = checkByText("""
    interface IERC20 {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C {
        struct S { uint256 a; }
        mapping(address => uint256) balances;
        S s;
        uint256[] arr;
        IERC20 token;
        function f(address who) public view returns (uint256) {
            S memory copy = s;
            copy.a = 5;
            S storage ptr = s;
            ptr = s;
            uint256 local = balances[who];
            local += arr.length;
            return local + copy.a + ptr.a + token.balanceOf(who) + block.timestamp + address(this).balance;
        }
    }
  """)

  fun testLocalShadowsStateVariable() = checkByText("""
    contract C {
        uint256 total;
        function f(uint256 total) public pure returns (uint256) {
            total = total + 1;
            return total;
        }
        function g() public pure returns (uint256) {
            uint256 total = 1;
            total += 1;
            return total;
        }
    }
  """)

  fun testOverloadsUseMostPermissiveCandidate() = checkByText("""
    contract C {
        uint256 x;
        function g(uint256 a) internal view returns (uint256) { return a + x; }
        function g(address a) internal returns (uint256) { x = 1; return uint160(a); }
        function f(uint256 a) public view returns (uint256) {
            return g(a);
        }
    }
  """)

  fun testLocalTupleAssignmentInPure() = checkByText("""
    contract C {
        uint256 a;
        function f() public pure returns (uint256) {
            uint256 x;
            uint256 y;
            (x, y) = (1, 2);
            return x + y;
        }
    }
  """)

  fun testUnresolvedCallIsNotReported() = checkByText("""
    contract C {
        function f() public view {
            unknown();
            something.call();
        }
    }
  """)

  fun testInlineAssemblyIsIgnored() = checkByText("""
    contract C {
        uint256 x;
        function f() public view returns (uint256 r) {
            assembly { sstore(0, 1) r := sload(0) }
        }
    }
  """)

  fun testThisSelectorIsPure() = checkByText("""
    contract C {
        function g() external {}
        function f() public pure returns (bytes4) {
            return this.g.selector;
        }
    }
  """)

  fun testOldPragmaIsIgnored() = checkByText("""
    pragma solidity ^0.4.24;
    contract C {
        uint256 total;
        function f() public view { total = 1; }
    }
  """)

  fun testRemoveViewFix() = checkFixByText(
    "Remove 'view'",
    """
    contract C {
        uint256 total;
        function f() public view { <error descr="$viewModifies"><caret>total</error> = 1; }
    }
    """,
    """
    contract C {
        uint256 total;
        function f() public { total = 1; }
    }
    """
  )

  fun testChangePureToViewFix() = checkFixByText(
    "Change 'pure' to 'view'",
    """
    contract C {
        uint256 total;
        function f() public pure returns (uint256) { return <error descr="$pureReads"><caret>total</error>; }
    }
    """,
    """
    contract C {
        uint256 total;
        function f() public view returns (uint256) { return total; }
    }
    """
  )
}
