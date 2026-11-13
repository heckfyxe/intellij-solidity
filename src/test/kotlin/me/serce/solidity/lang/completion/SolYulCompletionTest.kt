package me.serce.solidity.lang.completion

class SolYulCompletionTest : SolCompletionTestBase() {
  private fun variants(code: String): Set<String> {
    InlineFile(code).withCaret()
    return myFixture.completeBasic()?.map { it.lookupString }?.toSet() ?: emptySet()
  }

  fun testYulScope() {
    val variants = variants("""
        contract C {
            uint total;
            uint constant LIMIT = 1;
            function helper() internal {}
            function f(uint amount) public {
                uint local = 1;
                assembly {
                    let ptr := mload(0x40)
                    function double(v) -> w { w := add(v, v) }
                    { let hidden := 1 }
                    /*caret*/
                    let later := 1
                }
            }
        }
    """)
    assertContainsElements(variants, "ptr", "double", "amount", "local", "total", "LIMIT", "mstore", "codecopy", "datasize", "let", "if", "switch", "for", "function")
    assertDoesntContain(variants, "hidden", "later", "helper", "C", "require", "emit", "uint256", "msg", "this", "assembly", "break", "leave")
  }

  fun testInsideYulFunctionOnlyItsVariables() {
    val variants = variants("""
        contract C {
            uint constant LIMIT = 1;
            function f(uint amount) public {
                assembly {
                    let outer := 1
                    function g(a) -> r {
                        /*caret*/
                    }
                }
            }
        }
    """)
    assertContainsElements(variants, "a", "r", "g", "LIMIT", "add", "leave")
    assertDoesntContain(variants, "outer", "amount")
  }

  fun testBreakContinueInsideLoopBody() {
    val variants = variants("""
        contract C {
            function f() public {
                assembly {
                    for { let i := 0 } lt(i, 10) { i := add(i, 1) } {
                        /*caret*/
                    }
                }
            }
        }
    """)
    assertContainsElements(variants, "i", "break", "continue")
  }

  fun testSlotSuffix() {
    val variants = variants("""
        contract C {
            uint total;
            function f() public {
                assembly {
                    let s := total./*caret*/
                }
            }
        }
    """)
    assertContainsElements(variants, "slot", "offset")
  }

  fun testBuiltinWithArgumentsInsertsParentheses() = checkResultAfterCompletion("""
        contract C {
            function f() public {
                assembly {
                    calldatacop/*caret*/
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    calldatacopy(<caret>)
                }
            }
        }
    """)

  fun testBuiltinWithoutArgumentsPutsCaretAfter() = checkResultAfterCompletion("""
        contract C {
            function f() public {
                assembly {
                    let s := calldatasi/*caret*/
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    let s := calldatasize()<caret>
                }
            }
        }
    """)

  fun testYulFunctionInsertsParentheses() = checkResultAfterCompletion("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w { w := add(v, v) }
                    let r := doub/*caret*/
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w { w := add(v, v) }
                    let r := double(<caret>)
                }
            }
        }
    """)

  fun testYulVariable() = checkResultAfterCompletion("""
        contract C {
            function f() public {
                assembly {
                    let pointer := 0
                    mstore(poin/*caret*/, 1)
                }
            }
        }
    """, """
        contract C {
            function f() public {
                assembly {
                    let pointer := 0
                    mstore(pointer<caret>, 1)
                }
            }
        }
    """)

  fun testBuiltinTailShowsParameters() {
    InlineFile("""
        contract C {
            function f() public {
                assembly {
                    mstor/*caret*/
                }
            }
        }
    """).withCaret()
    val lookup = myFixture.completeBasic()!!.first { it.lookupString == "mstore" }
    val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(lookup)
    assertEquals("(p, v)", presentation.tailText)
  }
}
