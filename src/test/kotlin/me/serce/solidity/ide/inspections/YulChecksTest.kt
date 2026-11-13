package me.serce.solidity.ide.inspections

import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

/** Unused Yul variables, `.slot`-style suffixes and inspections in standalone Yul files. */
class YulChecksTest : SolTestBase() {
  private fun checkUnused(@Language("Solidity") code: String) {
    myFixture.configureByText("main.sol", code)
    myFixture.enableInspections(UnusedYulVariableInspection::class.java)
    myFixture.checkHighlighting(false, false, true)
  }

  private fun checkErrors(@Language("Solidity") code: String) {
    myFixture.configureByText("main.sol", code)
    myFixture.checkHighlighting(false, false, false)
  }

  // unused variables

  fun testUnusedLetVariable() = checkUnused("""
    contract C {
        function f() public {
            assembly {
                let <weak_warning descr="Variable 'x' is never used">x</weak_warning> := 1
                let y := 2
                mstore(0, y)
            }
        }
    }
  """)

  fun testAssignmentIsNotARead() = checkUnused("""
    contract C {
        function f() public {
            assembly {
                let <weak_warning descr="Variable 'x' is never used">x</weak_warning> := 1
                x := 2
                let a, <weak_warning descr="Variable 'b' is never used">b</weak_warning> := g()
                b := a
                function g() -> p, q {}
            }
        }
    }
  """)

  fun testReadInNestedBlockAndSelfUpdate() = checkUnused("""
    contract C {
        function f() public {
            assembly {
                let x := 1
                if 1 { x := add(x, 1) }
                for { let i := 0 } lt(i, 10) { i := add(i, 1) } {}
                let _ignored := 3
            }
        }
    }
  """)

  fun testUnusedYulFunctionParameter() = checkUnused("""
    contract C {
        function f() public {
            assembly {
                function g(a, <weak_warning descr="Parameter 'b' is never used">b</weak_warning>) -> r { r := a }
                pop(g(1, 2))
            }
        }
    }
  """)

  fun testShadowedNameInNestedFunctionIsNotARead() = checkUnused("""
    contract C {
        function f() public {
            assembly {
                let <weak_warning descr="Variable 'x' is never used">x</weak_warning> := 1
                function g(x) -> r { r := x }
                pop(g(2))
            }
        }
    }
  """)

  // suffixes

  fun testValidSuffixes() = checkErrors("""
    contract C {
        struct S { uint a; }
        uint x;
        uint[] arr;
        mapping(uint => uint) m;
        function f(uint[] calldata cd, bytes calldata b, function () external fp) external {
            S storage s;
            uint[] storage p = arr;
            assembly {
                pop(sload(x.slot))
                pop(x.offset)
                pop(arr.slot)
                pop(m.slot)
                pop(s.slot)
                pop(p.offset)
                pop(cd.offset)
                pop(cd.length)
                pop(b.length)
                pop(fp.address)
                pop(fp.selector)
            }
        }
    }
  """)

  fun testInvalidSuffixes() = checkErrors("""
    contract C {
        uint constant K = 1;
        uint immutable I;
        function f(uint v, bytes memory mem, uint[] calldata cd, function () internal ifp) internal {
            assembly {
                pop(v.<error descr="'.slot' can only be used with storage variables and storage pointers">slot</error>)
                pop(mem.<error descr="'.offset' can only be used with storage variables, storage pointers and calldata arrays">offset</error>)
                pop(mem.<error descr="'.length' can only be used with dynamic calldata arrays">length</error>)
                pop(cd.<error descr="'.slot' can only be used with storage variables and storage pointers">slot</error>)
                pop(K.<error descr="'.slot' can only be used with storage variables and storage pointers">slot</error>)
                pop(I.<error descr="'.offset' can only be used with storage variables, storage pointers and calldata arrays">offset</error>)
                pop(ifp.<error descr="'.selector' can only be used with external function pointers">selector</error>)
                pop(v.<error descr="'.address' can only be used with external function pointers">address</error>)
            }
        }
    }
  """)

  fun testUnknownKindsAreNotReported() = checkErrors("""
    contract C {
        function f(Unknown u) internal {
            assembly {
                pop(u.slot)
            }
        }
    }
  """)

  // standalone Yul files

  fun testInspectionsRunInYulFiles() {
    myFixture.configureByText("main.yul", """
      {
          function f(a, <weak_warning descr="Parameter 'b' is never used">b</weak_warning>) -> r { r := add(a, <warning descr="'y' is undefined">y</warning>) }
          let <weak_warning descr="Variable 'x' is never used">x</weak_warning> := f(1, 2)
          <warning descr="Function 'nope' is undefined">nope</warning>()
      }
    """.trimIndent())
    myFixture.enableInspections(ResolveNameInspection::class.java, UnusedYulVariableInspection::class.java)
    myFixture.checkHighlighting(true, false, true)
  }
}
