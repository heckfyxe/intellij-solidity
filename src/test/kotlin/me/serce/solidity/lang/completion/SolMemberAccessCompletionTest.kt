package me.serce.solidity.lang.completion

class SolMemberAccessCompletionTest : SolCompletionTestBase() {

  fun testEmptyCompletion() = checkCompletion(hashSetOf("doSmth"), """
        contract SomeContract {
            function doSmth() public {
            
            }        
        }
        
        contract Test {
            function doSmth(SomeContract c) {
                c./*caret*/
            }
        }
  """)

  fun testFunctionCompletion() = checkCompletion(hashSetOf("doSmth3"), """
        contract SomeContract {
            function doSmth() public {
            
            }

            function doSmth3() public {
            
            } 
        }
        
        contract Test {
            function doSmth(SomeContract c) {
                c.doS/*caret*/
            }
        }
  """)

  fun testPublicStateVarCompletion() = checkCompletion(hashSetOf("smth", "smth2"), """
        contract SomeContract {
            uint public smth;
            uint public smth2;
        }
        
        contract Test {
            function doSmth(SomeContract c) {
                c.sm/*caret*/
            }
        }
  """, strict = true)

  fun testOnlyPublicStateVarCompletion() = checkCompletion(hashSetOf("smthPublic1", "smthPublic2"), """
        contract SomeContract {
            uint smth;
            uint public smthPublic1;
            uint public smthPublic2;
        }
        
        contract Test {
            function doSmth(SomeContract c) {
                c.sm/*caret*/
            }
        }
  """, strict = true)

  fun testOnlyPublicFunctionCompletion() = checkCompletion(hashSetOf("doSmth1", "doSmth2"), """
        contract SomeContract {
            function doSmthInternal() internal {
            
            }

            function doSmthPrivate() private {
            
            }

            function doSmth1() public {
            
            }
                    
            function doSmth2() public {
            
            }         
        }
        
        contract Test {
            function doFunc(SomeContract c) {
                c.doS/*caret*/
            }
        }
  """, strict = true)

  fun testOverridesOnlyOnce() {
    InlineFile("""
        contract BaseContract {
            function doSmth1() public {
            
            } 
        }
        contract SomeContract is BaseContract {
            function doSmth1() public {
            
            }
                    
            function doSmth2() public {
            
            }         
        }
        
        contract Test {
            function doFunc(SomeContract c) {
                c.doS/*caret*/
            }
        }
  """).withCaret()
    val variants = myFixture.completeBasic()
      .map { it.lookupString }
      .groupBy { it }
      .mapValues { it.value.size }
    assertEquals(variants["doSmth1"], 1)
  }

  fun testWrapUserDefinedType() = checkCompletion(hashSetOf("wrap", "unwrap"), """
        contract B {
          type ShortString is bytes32;
        
          function a()  {
            ShortString./*caret*/;
          }
        }
        
  """)

  private val addressMembers = setOf("balance", "code", "codehash", "call", "delegatecall", "send", "staticcall", "transfer")

  fun testAddressMembersOnlyNoKeywords() = checkCompletion(addressMembers, """
        contract C {
            function f(address source) public {
                source./*caret*/
            }
        }
  """, strict = true)

  /** With another statement on the next line, `source.` must not be read as the type of a variable declaration. */
  fun testAddressMembersBeforeNextStatement() = checkCompletion(addressMembers, """
        contract C {
            function f(address source) public returns (uint256) {
                (bool ok, bytes memory result) = source.call("");
                source./*caret*/
                require(ok, "call failed");
                return 0;
            }
        }
  """, strict = true)

  fun testContractMembersBeforeNextStatement() = checkCompletion(hashSetOf("doSmth"), """
        contract SomeContract {
            function doSmth() public {}
        }

        contract Test {
            function f(SomeContract c) public {
                c./*caret*/
                require(true, "x");
            }
        }
  """)

  fun testTypedPrefixBeforeNextStatement() = checkResultAfterCompletion("""
        contract C {
            function f(address source) public {
                source.bal/*caret*/
                require(true, "x");
            }
        }
  """, """
        contract C {
            function f(address source) public {
                source.balance<caret>
                require(true, "x");
            }
        }
  """)
}
