package me.serce.solidity.lang.completion

class SolBuiltinMemberCompletionTest : SolCompletionTestBase() {

  fun testErrorSelector() = checkResultAfterCompletion("""
        contract C {
            error Unauthorized();
            function f() public {
                Unauthorized.sel/*caret*/;
            }
        }
  """, """
        contract C {
            error Unauthorized();
            function f() public {
                Unauthorized.selector;
            }
        }
  """)

  fun testEventSelector() = checkResultAfterCompletion("""
        interface I { event Transfer(address to); }
        contract C {
            function f() public {
                I.Transfer.sel/*caret*/;
            }
        }
  """, """
        interface I { event Transfer(address to); }
        contract C {
            function f() public {
                I.Transfer.selector;
            }
        }
  """)

  fun testIntegerMetaType() = checkCompletion(hashSetOf("min", "max"), """
        contract C {
            function f() public {
                type(uint8)./*caret*/;
            }
        }
  """, strict = true)

  fun testContractMetaType() = checkCompletion(hashSetOf("name", "creationCode", "runtimeCode"), """
        contract C {
            function f() public {
                type(C)./*caret*/;
            }
        }
  """, strict = true)

  fun testInterfaceMetaType() = checkCompletion(hashSetOf("name", "interfaceId"), """
        interface I {}
        contract C {
            function f() public {
                type(I)./*caret*/;
            }
        }
  """, strict = true)

  fun testFixedBytesLength() = checkResultAfterCompletion("""
        contract C {
            function f(bytes4 b) public {
                b.len/*caret*/;
            }
        }
  """, """
        contract C {
            function f(bytes4 b) public {
                b.length;
            }
        }
  """)

  fun testBytesConcat() = checkCompletion(hashSetOf("concat"), """
        contract C {
            function f() public {
                bytes./*caret*/;
            }
        }
  """)
}
