package me.serce.solidity.ide.refactoring

import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

class SolYulRenameTest : SolTestBase() {
  fun testYulVariable() = doTest("ptr", """
        contract C {
            function f() public {
                assembly {
                    let /*caret*/m := mload(0x40)
                    mstore(m, 1)
                    if m { m := add(m, 0x20) }
                    function g(m) -> r { r := m }
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    let ptr := mload(0x40)
                    mstore(ptr, 1)
                    if ptr { ptr := add(ptr, 0x20) }
                    function g(m) -> r { r := m }
                }
            }
        }
    """)

  fun testYulVariableFromUsage() = doTest("i", """
        contract C {
            function f() public {
                assembly {
                    for { let j := 0 } lt(j, 10) { j := add(/*caret*/j, 1) } { mstore(j, 0) }
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    for { let i := 0 } lt(i, 10) { i := add(i, 1) } { mstore(i, 0) }
                }
            }
        }
    """)

  fun testYulFunction() = doTest("twice", """
        contract C {
            function f() public {
                assembly {
                    let r := double(2)
                    function /*caret*/double(v) -> w { w := add(v, v) }
                    { pop(double(r)) }
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    let r := twice(2)
                    function twice(v) -> w { w := add(v, v) }
                    { pop(twice(r)) }
                }
            }
        }
    """)

  fun testYulFunctionFromCall() = doTest("twice", """
        contract C {
            function f() public {
                assembly {
                    let r := /*caret*/double(2)
                    function double(v) -> w { w := add(v, v) }
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    let r := twice(2)
                    function twice(v) -> w { w := add(v, v) }
                }
            }
        }
    """)

  fun testYulFunctionParameter() = doTest("value", """
        contract C {
            function f() public {
                assembly {
                    function double(/*caret*/v) -> w { w := add(v, v) }
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    function double(value) -> w { w := add(value, value) }
                }
            }
        }
    """)

  fun testSolidityParameterUsedInAssembly() = doTest("total", """
        contract C {
            uint s;
            function f(uint /*caret*/amount) public {
                assembly {
                    sstore(s.slot, amount)
                }
                amount = 1;
            }
        }
    """, """
        contract C {
            uint s;
            function f(uint total) public {
                assembly {
                    sstore(s.slot, total)
                }
                total = 1;
            }
        }
    """)

  fun testStateVariableUsedWithSlot() = doTest("counter", """
        contract C {
            uint /*caret*/s;
            function f() public {
                assembly {
                    sstore(s.slot, add(sload(s.slot), 1))
                }
            }
        }
    """, """
        contract C {
            uint counter;
            function f() public {
                assembly {
                    sstore(counter.slot, add(sload(counter.slot), 1))
                }
            }
        }
    """)

  private fun doTest(newName: String, @Language("Solidity") before: String, @Language("Solidity") after: String) {
    InlineFile(before).withCaret()
    myFixture.renameElementAtCaret(newName)
    myFixture.checkResult(after)
  }
}
