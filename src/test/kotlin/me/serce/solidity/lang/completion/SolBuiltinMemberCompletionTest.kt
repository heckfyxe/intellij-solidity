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

  fun testStringConcat() = checkCompletion(hashSetOf("concat"), """
        contract C {
            function f() public {
                string./*caret*/;
            }
        }
  """, strict = true)

  fun testNoConcatOnBytesVariable() {
    InlineFile("""
        contract C {
            function f(bytes memory b, string memory s) public {
                b./*caret*/;
            }
        }
    """).withCaret()
    val completions = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse("$completions shouldn't contain concat", "concat" in completions)
    assertTrue("$completions should contain length", "length" in completions)
  }

  fun testNoConcatOnStringVariable() {
    InlineFile("""
        contract C {
            function f(string memory s) public {
                s./*caret*/;
            }
        }
    """).withCaret()
    val completions = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse("$completions shouldn't contain concat", "concat" in completions)
  }

  fun testAbstractContractMetaType() = checkCompletion(hashSetOf("name"), """
        abstract contract A {}
        contract C {
            function f() public {
                type(A)./*caret*/;
            }
        }
  """, strict = true)

  fun testEnumMetaTypeBefore088() {
    InlineFile("""
        pragma solidity ^0.8.0 <0.8.8;
        contract C {
            enum K { A, B }
            function f() public {
                type(K)./*caret*/;
            }
        }
    """).withCaret()
    val completions = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse("$completions shouldn't contain max", "max" in completions)
  }

  fun testBytesConcat() = checkCompletion(hashSetOf("concat"), """
        contract C {
            function f() public {
                bytes./*caret*/;
            }
        }
  """)
}
