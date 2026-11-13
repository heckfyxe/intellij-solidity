package me.serce.solidity.lang.core.resolve

import me.serce.solidity.lang.psi.SolYulFunctionCall
import me.serce.solidity.lang.psi.SolYulPath

class SolYulResolveTest : SolResolveTestBase() {
  fun testLetVariable() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let x := 1
                      //x
                    let y := add(x, 2)
                               //^
                }
            }
        }
  """)

  fun testMultipleLetVariables() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    function g() -> a, b {}
                    let p, q := g()
                         //x
                    mstore(q, p)
                         //^
                }
            }
        }
  """)

  fun testVariableNotVisibleBeforeDeclaration() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    mstore(0, x)
                            //^unresolved
                    let x := 1
                }
            }
        }
  """)

  fun testVariableNotVisibleInItsOwnInitializer() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let x := add(x, 1)
                               //^unresolved
                }
            }
        }
  """)

  fun testVariableVisibleInNestedBlock() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let x := 1
                      //x
                    if x {
                        {
                            mstore(0, x)
                                    //^
                        }
                    }
                }
            }
        }
  """)

  fun testVariableOfNestedBlockNotVisibleOutside() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    { let x := 1 }
                    mstore(0, x)
                            //^unresolved
                }
            }
        }
  """)

  fun testInnerVariableShadowsSolidityLocal() = checkByCode("""
        contract C {
            function f() public {
                uint x;
                assembly {
                    let x := 1
                      //x
                    mstore(0, x)
                            //^
                }
            }
        }
  """)

  fun testAssignmentTarget() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let x
                      //x
                    x := 1
                  //^
                }
            }
        }
  """)

  fun testSolidityLocal() = checkByCode("""
        contract C {
            function f() public {
                uint amount = 1;
                    //x
                assembly {
                    mstore(0, amount)
                            //^
                }
            }
        }
  """)

  fun testSolidityParameter() = checkByCode("""
        contract C {
            function f(uint amount) public {
                           //x
                assembly {
                    mstore(0, amount)
                            //^
                }
            }
        }
  """)

  fun testSolidityReturnVariableAssigned() = checkByCode("""
        contract C {
            function f() public returns (uint result) {
                                              //x
                assembly {
                    result := 1
                    //^
                }
            }
        }
  """)

  fun testStateVariableSlot() = checkByCode("""
        contract C {
            uint total;
                //x
            function f() public {
                assembly {
                    sstore(total.slot, 1)
                           //^
                }
            }
        }
  """)

  fun testCalldataOffset() = checkByCode("""
        contract C {
            function f(bytes calldata data) external {
                                       //x
                assembly {
                    calldatacopy(0, data.offset, data.length)
                                    //^
                }
            }
        }
  """)

  fun testSolidityFunctionIsNotAVariable() = checkByCode("""
        contract C {
            function g() public {}
            function f() public {
                assembly {
                    mstore(0, g)
                            //^unresolved
                }
            }
        }
  """)

  fun testFunctionCall() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w { w := add(v, v) }
                               //x
                    let r := double(2)
                              //^
                }
            }
        }
  """)

  fun testFunctionCallBeforeDefinition() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    let r := double(2)
                              //^
                    function double(v) -> w { w := add(v, v) }
                               //x
                }
            }
        }
  """)

  fun testFunctionVisibleInNestedBlock() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w { w := add(v, v) }
                               //x
                    if 1 {
                        pop(double(2))
                             //^
                    }
                }
            }
        }
  """)

  fun testRecursiveFunction() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    function fact(n) -> r {
                              //x
                        r := 1
                        if n { r := mul(n, fact(sub(n, 1))) }
                                           //^
                    }
                }
            }
        }
  """)

  fun testFunctionOfNestedBlockNotVisibleOutside() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    { function inner() {} }
                    inner()
                    //^unresolved
                }
            }
        }
  """)

  fun testFunctionParameter() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w {
                                  //x
                        w := add(v, v)
                               //^
                    }
                }
            }
        }
  """)

  fun testFunctionReturnVariable() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w {
                                        //x
                        w := add(v, v)
                      //^
                    }
                }
            }
        }
  """)

  fun testForInitVisibleInCondition() = checkByCode(forLoop(cond = "//^"))

  fun testForInitVisibleInPost() = checkByCode(forLoop(post = "//^"))

  fun testForInitVisibleInBody() = checkByCode(forLoop(body = "//^"))

  private fun forLoop(cond: String = "", post: String = "", body: String = "") = """
        contract C {
            function f() public {
                assembly {
                    for { let i := 0 }
                            //x
                        lt(i, 10)
                         $cond
                        { i := add(i, 1) }
                                 $post
                        { mstore(i, 0) }
                               $body
                }
            }
        }
  """

  fun testFunctionDoesNotSeeOuterYulVariable() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let outer := 1
                    function g() -> r { r := outer }
                                             //^unresolved
                }
            }
        }
  """)

  fun testFunctionDoesNotSeeSolidityLocal() = checkByCode("""
        contract C {
            function f(uint amount) public {
                assembly {
                    function g() -> r { r := amount }
                                             //^unresolved
                }
            }
        }
  """)

  fun testFunctionSeesConstant() = checkByCode("""
        contract C {
            uint constant LIMIT = 10;
                            //x
            function f() public {
                assembly {
                    function g() -> r { r := LIMIT }
                                             //^
                }
            }
        }
  """)

  fun testForInitVariableNotVisibleAfterLoop() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    for { let i := 0 } lt(i, 10) { i := add(i, 1) } {}
                    mstore(0, i)
                            //^unresolved
                }
            }
        }
  """)

  fun testSwitchCaseSeesOuterVariable() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    let x := calldataload(0)
                      //x
                    switch x
                    case 0 { mstore(0, x) }
                                     //^
                    default { revert(0, 0) }
                }
            }
        }
  """)

  fun testBuiltinCallIsNotResolvedToADeclaration() = checkFunctionCall("""
        contract C {
            function f() public {
                assembly {
                    codecopy(0, 0, codesize())
                    //^unresolved
                }
            }
        }
  """)

  fun testStandaloneYulFile() {
    myFixture.configureByText("a.yul", """
        {
            function f(a) -> b { b := a }
            let x := f(1)
        }
    """.trimIndent())
    val call = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, SolYulFunctionCall::class.java).single()
    assertEquals("f", (call.reference?.resolve() as? me.serce.solidity.lang.psi.SolNamedElement)?.name)
    val path = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, SolYulPath::class.java).first { it.text == "a" }
    assertNotNull(path.reference?.resolve())
  }

  fun testLegacyStackAssignment() = checkByCode("""
        contract C {
            function f() public {
                uint b;
                   //x
                assembly { =: b }
                            //^
            }
        }
  """)

  fun testLegacyLabel() = checkByCode("""
        contract C {
            function f() public {
                assembly {
                    jump(loopend)
                       //^
                    loop:
                    loopend :
                  //x
                }
            }
        }
  """)

  private fun checkFunctionCall(code: String) = checkByCodeInternal<SolYulFunctionCall, me.serce.solidity.lang.psi.SolNamedElement>(code)
}
