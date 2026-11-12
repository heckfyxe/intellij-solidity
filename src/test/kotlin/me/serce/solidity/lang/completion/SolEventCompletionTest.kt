package me.serce.solidity.lang.completion

import org.intellij.lang.annotations.Language

class SolEventCompletionTest : SolCompletionTestBase() {

  fun testEventCompletion() = checkCompletion(hashSetOf("BaseEvent"), """
        contract Base {
            event BaseEvent();

            function emitEvent() {
                emit /*caret*/
            }
        }
  """)

  fun testEventWithInheritance() = checkCompletion(hashSetOf("BaseEvent", "ChildEvent"), """
        contract BaseContract {
            event BaseEvent();
        }

        contract ChildContract is BaseContract {

            event ChildEvent();

            function emitEvent() {
                emit /*caret*/
            }
        }
  """)

  fun testEventExactCompletion() = checkCompletion(hashSetOf("ChildEvent", "CopyEvent"), """
        contract BaseContract {
            event BaseEvent();
        }

        contract ChildContract is BaseContract {

            event ChildEvent();
            event CopyEvent();

            function emitEvent() {
                emit C/*caret*/
            }
        }
  """)

  fun testEmitOffersOnlyVisibleEventsAndQualifiers() = checkCompletion(hashSetOf("Mine", "Free", "Other"), """
        event Free(uint a);

        contract Other {
            event OtherEvent();
        }

        contract NoEvents {}

        contract C {
            event Mine(uint a);

            function f() public {
                emit /*caret*/
            }
        }
  """, strict = true)

  fun testEmitQualifiedMember() = checkCompletion(hashSetOf("Transfer", "Approval"), """
        interface IBase {
            event Approval(address owner);
        }

        interface IToken is IBase {
            event Transfer(address from, address to);
        }

        contract C {
            function f() public {
                emit IToken./*caret*/
            }
        }
  """, strict = true)

  fun testEmitInsertsParenthesesAndSemicolon() = checkResultAfterCompletion("""
        contract C {
            event Transfer(address from, uint256 value);
            function f() public {
                emit Tra/*caret*/
            }
        }
  """, """
        contract C {
            event Transfer(address from, uint256 value);
            function f() public {
                emit Transfer(<caret>);
            }
        }
  """)

  fun testEmitWithoutParametersPutsCaretAfterSemicolon() = checkResultAfterCompletion("""
        contract C {
            event Paused();
            function f() public {
                emit Pau/*caret*/
            }
        }
  """, """
        contract C {
            event Paused();
            function f() public {
                emit Paused();<caret>
            }
        }
  """)

  fun testEmitStepsIntoExistingParentheses() = checkResultAfterCompletion("""
        contract C {
            event Transfer(address from, uint256 value);
            function f() public {
                emit Tra/*caret*/(a, 1);
            }
        }
  """, """
        contract C {
            event Transfer(address from, uint256 value);
            function f() public {
                emit Transfer(<caret>a, 1);
            }
        }
  """)

  fun testEmitKeepsExistingSemicolon() = checkResultAfterCompletion("""
        contract C {
            event Paused();
            function f() public {
                emit Pau/*caret*/;
            }
        }
  """, """
        contract C {
            event Paused();
            function f() public {
                emit Paused();<caret>
            }
        }
  """)

  fun testEmitQualifierInsertsDot() = checkResultAfterCompletion("""
        interface IToken {
            event Transfer(address from, address to);
        }

        contract C {
            function f() public {
                emit ITo/*caret*/
            }
        }
  """, """
        interface IToken {
            event Transfer(address from, address to);
        }

        contract C {
            function f() public {
                emit IToken.<caret>
            }
        }
  """)

  fun testParameterTypes() = checkCompletion(hashSetOf("address ", "uint256 ", "bool ", "bytes32 ", "string "), """
        contract C {
            event E(/*caret*/);
        }
  """)

  fun testParameterTypesInUnclosedList() = checkCompletion(hashSetOf("uint256 ", "uint128 "), """
        contract C {
            event E(address a, uin/*caret*/
        }
  """)

  fun testErrorParameterTypes() = checkCompletion(hashSetOf("address ", "uint256 "), """
        contract C {
            error E(/*caret*/);
        }
  """)

  fun testIndexedAfterType() = checkCompletion(hashSetOf("indexed"), """
        contract C {
            event E(address /*caret*/);
        }
  """)

  fun testIndexedInUnclosedList() = checkCompletion(hashSetOf("indexed"), """
        contract C {
            event E(address /*caret*/
        }
  """)

  fun testNoIndexedAfterIndexed() = checkNoCompletion("indexed", """
        contract C {
            event E(address indexed /*caret*/);
        }
  """)

  fun testNoIndexedInErrors() = checkNoCompletion("indexed", """
        contract C {
            error E(address /*caret*/);
        }
  """)

  fun testNoIndexedBeyondThreeTopics() = checkNoCompletion("indexed", """
        contract C {
            event E(address indexed a, address indexed b, uint256 indexed c, uint256 /*caret*/);
        }
  """)

  fun testFourthIndexedInAnonymousEvent() = checkCompletion(hashSetOf("indexed"), """
        contract C {
            event E(address indexed a, address indexed b, uint256 indexed c, uint256 /*caret*/) anonymous;
        }
  """)

  fun testAnonymousAfterParameters() = checkResultAfterCompletion("""
        contract C {
            event E(uint256 a) anon/*caret*/
        }
  """, """
        contract C {
            event E(uint256 a) anonymous<caret>
        }
  """)

  private fun checkNoCompletion(unexpected: String, @Language("Solidity") code: String) {
    InlineFile(code).withCaret()
    val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse("$variants contains $unexpected", unexpected in variants)
  }
}
