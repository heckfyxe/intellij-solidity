package me.serce.solidity.ide.inspections

class RestrictableStateMutabilityInspectionTest : SolInspectionsTestBase(RestrictableStateMutabilityInspection()) {
  private fun pure(name: String) =
    "/*@weak_warning descr=\"Function state mutability can be restricted to pure\"@*/$name/*@/weak_warning@*/"

  private fun view(name: String) =
    "/*@weak_warning descr=\"Function state mutability can be restricted to view\"@*/$name/*@/weak_warning@*/"

  private fun check(code: String) = checkByText(code, checkWeakWarn = true)

  fun testCanBePure() = check("""
    contract C {
        function ${pure("add")}(uint256 a, uint256 b) public returns (uint256) {
            return a + b;
        }
        function ${pure("empty")}() internal {}
        function ${pure("hash")}(bytes memory data) external returns (bytes32) {
            require(data.length > 0, "empty");
            return keccak256(abi.encodePacked(data));
        }
    }
  """)

  fun testCanBeView() = check("""
    interface IERC20 {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C {
        uint256 total;
        mapping(address => uint256) balances;
        IERC20 token;
        function ${view("getTotal")}() public returns (uint256) {
            return total;
        }
        function ${view("balanceOfBoth")}(address who) public returns (uint256) {
            return balances[who] + token.balanceOf(who);
        }
        function ${view("sender")}() public returns (address) {
            return msg.sender;
        }
        function ${view("time")}() public returns (uint256) {
            return block.timestamp;
        }
        function ${view("self")}() public returns (uint256) {
            return address(this).balance;
        }
    }
  """)

  fun testStoragePointerReadCanOnlyBeView() = check("""
    library Counters {
        struct Counter { uint256 _value; }
        function ${view("current")}(Counter storage counter) internal returns (uint256) {
            return counter._value;
        }
        function increment(Counter storage counter) internal {
            counter._value += 1;
        }
    }
  """)

  fun testModifyingFunctionsAreNotReported() = check("""
    interface IERC20 {
        function transfer(address to, uint256 amount) external returns (bool);
    }
    contract D {}
    contract C {
        uint256 total;
        uint256[] items;
        event E();
        IERC20 token;
        function a() public { total = 1; }
        function b() public { items.push(1); }
        function c() public { emit E(); }
        function d() public { new D(); }
        function e(address payable to) public { to.transfer(1); }
        function f() public { token.transfer(msg.sender, 1); }
        function g() public { a(); }
        function h(address to) public { (bool ok, ) = to.call(""); require(ok); }
        function i() public { delete total; }
        function j() public { total++; }
    }
  """)

  fun testSkippedFunctions() = check("""
    interface I {
        function f() external returns (uint256);
    }
    abstract contract Base {
        function v() public virtual returns (uint256) { return 1; }
        function o() public virtual returns (uint256) { return 1; }
        function unimplemented() public virtual returns (uint256);
    }
    contract C is Base, I {
        uint256 x;
        address owner;
        modifier onlyOwner() { require(msg.sender == owner); _; }
        constructor() {}
        receive() external payable {}
        fallback() external {}
        function o() public override returns (uint256) { return 2; }
        function f() external returns (uint256) { return 3; }
        function unimplemented() public override returns (uint256) { return 4; }
        function withModifier() public onlyOwner returns (uint256) { return 5; }
        function pay() public payable returns (uint256) { return 6; }
        function value() public returns (uint256) { return msg.value; }
        function asm() public returns (uint256 r) { assembly { r := 1 } }
        function unresolved() public returns (uint256) { return unknownFunction(); }
        function alreadyView() public view returns (uint256) { return x; }
        function alreadyPure() public pure returns (uint256) { return 1; }
    }
  """)

  fun testOldPragmaIsIgnored() = check("""
    pragma solidity ^0.4.24;
    contract C {
        function add(uint a, uint b) public returns (uint) { return a + b; }
    }
  """)

  fun testAddPureFix() = checkFixByText(
    "Add 'pure'",
    """
    contract C {
        function <weak_warning descr="Function state mutability can be restricted to pure"><caret>add</weak_warning>(uint256 a, uint256 b) public returns (uint256) { return a + b; }
    }
    """,
    """
    contract C {
        function add(uint256 a, uint256 b) public pure returns (uint256) { return a + b; }
    }
    """,
    checkWeakWarn = true
  )

  fun testAddViewFixWithoutVisibility() = checkFixByText(
    "Add 'view'",
    """
    uint256 constant X = 1;
    contract C {
        uint256 total;
        function <weak_warning descr="Function state mutability can be restricted to view"><caret>get</weak_warning>() returns (uint256) { return total; }
    }
    """,
    """
    uint256 constant X = 1;
    contract C {
        uint256 total;
        function get() view returns (uint256) { return total; }
    }
    """,
    checkWeakWarn = true
  )
}
