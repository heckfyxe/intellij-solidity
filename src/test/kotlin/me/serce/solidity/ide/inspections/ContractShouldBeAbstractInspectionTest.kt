package me.serce.solidity.ide.inspections

class ContractShouldBeAbstractInspectionTest : SolInspectionsTestBase(ContractShouldBeAbstractInspection()) {
  private fun err(name: String) = "/*@error descr=\"Contract should be marked as abstract\"@*/$name/*@/error@*/"

  fun testOwnUnimplementedFunction() = checkByText("""
    contract ${err("A")} {
        function f() public virtual;
    }
  """)

  fun testInheritedInterfaceFunction() = checkByText("""
    interface I {
        function f() external;
        function g(uint256 x) external returns (uint256);
    }
    contract ${err("A")} is I {
        function f() external {}
    }
  """)

  fun testInheritedAbstractFunctionThroughChain() = checkByText("""
    abstract contract Base {
        function hook() internal virtual;
    }
    abstract contract Middle is Base {}
    contract ${err("Leaf")} is Middle {}
  """)

  fun testOverloadWithDifferentParametersDoesNotImplement() = checkByText("""
    interface I {
        function f(uint256 x) external;
    }
    contract ${err("A")} is I {
        function f(address x) external {}
    }
  """)

  fun testImplementedEverything() = checkByText("""
    interface IERC20 {
        function totalSupply() external view returns (uint256);
        function balanceOf(address account) external view returns (uint256);
        function transfer(address to, uint256 amount) external returns (bool);
    }
    abstract contract Context {
        function _msgSender() internal view virtual returns (address) { return msg.sender; }
    }
    contract ERC20 is Context, IERC20 {
        mapping(address => uint256) private _balances;
        uint256 private _totalSupply;
        function totalSupply() public view virtual override returns (uint256) { return _totalSupply; }
        function balanceOf(address account) public view virtual override returns (uint256) { return _balances[account]; }
        function transfer(address to, uint256 amount) public virtual override returns (bool) {
            _balances[_msgSender()] -= amount;
            _balances[to] += amount;
            return true;
        }
    }
    contract Token is ERC20 {}
  """)

  fun testImplementedInIntermediateBase() = checkByText("""
    abstract contract Base {
        function hook(uint x) internal virtual;
    }
    abstract contract Impl is Base {
        function hook(uint256 x) internal virtual override {}
    }
    contract Leaf is Impl {}
  """)

  fun testPublicStateVariableImplementsGetter() = checkByText("""
    interface I {
        function owner() external view returns (address);
        function balances(address who) external view returns (uint256);
    }
    contract A is I {
        address public override owner;
        mapping(address => uint256) public override balances;
    }
  """)

  fun testOverloadsImplementedSeparately() = checkByText("""
    interface I {
        function f(uint256 x) external;
        function f(address x) external;
    }
    contract A is I {
        function f(uint256 x) external {}
        function f(address x) external {}
    }
  """)

  fun testStructParameters() = checkByText("""
    struct S { uint256 a; }
    interface I {
        function f(S calldata s) external;
    }
    contract A is I {
        function f(S calldata s) external {}
    }
  """)

  fun testAbstractInterfaceAndLibraryAreIgnored() = checkByText("""
    abstract contract A {
        function f() public virtual;
    }
    interface I {
        function f() external;
    }
    library L {
        function g() internal pure returns (uint256) { return 1; }
    }
  """)

  fun testUnresolvedBaseIsIgnored() = checkByText("""
    contract A is Unknown {
        function f() public virtual;
    }
  """)

  fun testOldPragmaIsIgnored() = checkByText("""
    pragma solidity ^0.5.0;
    contract A {
        function f() public;
    }
  """)

  fun testMakeContractAbstractFix() = checkFixByText(
    "Make contract abstract",
    """
    contract <error descr="Contract should be marked as abstract"><caret>A</error> {
        function f() public virtual;
    }
    """,
    """
    abstract contract A {
        function f() public virtual;
    }
    """
  )
}
