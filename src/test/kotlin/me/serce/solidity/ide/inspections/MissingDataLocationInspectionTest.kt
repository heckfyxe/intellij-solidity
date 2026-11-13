package me.serce.solidity.ide.inspections

class MissingDataLocationInspectionTest : SolInspectionsTestBase(MissingDataLocationInspection()) {
  private fun err(context: String, code: String, storage: Boolean = false): String {
    val locations = if (storage) "\\\"memory\\\", \\\"storage\\\" or \\\"calldata\\\"" else "\\\"memory\\\" or \\\"calldata\\\""
    return "/*@error descr=\"Data location must be $locations for $context, but none was given\"@*/$code/*@/error@*/"
  }

  private fun variable(code: String) = err("variable", code, storage = true)

  fun testParameters() = checkByText("""
    contract C {
        struct S { uint256 a; }
        function f(${err("parameter in function", "string s")}, ${err("parameter in function", "uint256[] a")}, uint256 x) public {}
        function g(${err("parameter in external function", "bytes b")}, ${err("parameter in external function", "S s")}) external {}
        function h(${err("parameter in function", "S s", storage = true)}) internal {}
        function r() public returns (${err("return parameter in function", "string")}) {}
    }
  """)

  fun testLocalVariables() = checkByText("""
    contract C {
        struct S { uint256 a; }
        mapping(uint256 => S) items;
        function f() public {
            ${variable("string s")} = "a";
            ${variable("uint256[] memoryLess")};
            ${variable("S item")} = items[1];
            (${variable("bytes b")}, uint256 x) = (new bytes(1), 1);
            /*@error descr="Data location must be \"storage\" for variable, but none was given"@*/mapping(uint256 => S) m/*@/error@*/ = items;
        }
    }
  """)

  fun testValidDeclarations() = checkByText("""
    interface I {
        function f(string calldata s) external;
        function g(bytes b) external;
    }
    contract C {
        enum E { A }
        struct S { uint256 a; bytes data; }
        type Price is uint256;
        event Ev(string s, uint256[] a);
        error Err(string reason);
        string name;
        uint256[] items;
        S state;
        mapping(uint256 => S) map;
        function unimplemented(string s) public virtual;
        function f(string memory s, uint256[] calldata a, bytes32 h, C c, E e, Price p, function(uint256) external fn) public returns (bytes memory, S memory) {
            S storage ptr = state;
            uint256[] storage list = items;
            mapping(uint256 => S) storage m = map;
            (string memory x, uint256 y) = (s, 1);
            uint256 z = a.length;
        }
        modifier m(uint256 x) { _; }
    }
  """)

  fun testLibraryPublicFunctionAllowsStorage() = checkByText("""
    library L {
        struct S { uint256 a; }
        function f(${err("parameter in function", "S s", storage = true)}) public {}
    }
  """)

  fun testUnresolvedTypeIsIgnored() = checkByText("""
    contract C {
        function f(Unknown u) public {
            Unknown v;
        }
    }
  """)

  fun testOldPragmaIsIgnored() = checkByText("""
    pragma solidity ^0.4.24;
    contract C {
        function f(string s) public {
            uint[] a;
        }
    }
  """)

  fun testAddMemoryFix() = checkFixByText(
    "Add 'memory'",
    """
    contract C {
        function f() public {
            <error descr="Data location must be \"memory\", \"storage\" or \"calldata\" for variable, but none was given"><caret>string s</error> = "a";
        }
    }
    """,
    """
    contract C {
        function f() public {
            string memory s = "a";
        }
    }
    """
  )

  fun testAddCalldataFix() = checkFixByText(
    "Add 'calldata'",
    """
    contract C {
        function f(<error descr="Data location must be \"memory\" or \"calldata\" for parameter in external function, but none was given"><caret>uint256[] a</error>) external {}
    }
    """,
    """
    contract C {
        function f(uint256[] calldata a) external {}
    }
    """
  )
}
