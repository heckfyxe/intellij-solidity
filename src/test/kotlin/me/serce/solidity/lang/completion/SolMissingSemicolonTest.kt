package me.serce.solidity.lang.completion

class SolMissingSemicolonTest : SolCompletionTestBase() {
  fun testCompletionInStatementWithoutSemicolon() = checkResultAfterCompletion("""
        contract C {
            mapping(uint => address) s_approved;
            address _owner;

            function f(uint _tokenId) public {
                require(_owner == msg.sender || s_appr/*caret*/)
                uint x = 1;
            }
        }
  """, """
        contract C {
            mapping(uint => address) s_approved;
            address _owner;

            function f(uint _tokenId) public {
                require(_owner == msg.sender || s_approved<caret>)
                uint x = 1;
            }
        }
  """)

  fun testCompletionAfterStatementWithoutSemicolon() = checkCompletion(hashSetOf("sender"), """
        contract C {
            mapping(uint => address) s_approved;
            address _owner;

            function f(uint _tokenId) public {
                require(_owner == msg.sender || s_approved[_tokenId] == msg.sender)
                address a = msg./*caret*/;
            }
        }
  """)

  fun testCompletionAfterStateVariableWithoutSemicolon() = checkResultAfterCompletion("""
        contract C {
            uint a
            address owner;

            function f() public {
                own/*caret*/;
            }
        }
  """, """
        contract C {
            uint a
            address owner;

            function f() public {
                owner<caret>;
            }
        }
  """)

  fun testMissingSemicolonIsTheOnlyError() {
    InlineFile("""
        contract C {
            mapping(uint => address) s_approved;
            address _owner;

            function f(uint _tokenId) public view returns (uint) {
                require(_owner == msg.sender || s_approved[_tokenId] == msg.sender)<EOLError descr="';' expected, got 'uint'"></EOLError>
                uint x = _tokenId + 1;
                return x;
            }
        }
    """)
    myFixture.checkHighlighting()
  }
}
