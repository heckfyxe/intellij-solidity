package me.serce.solidity.lang.completion

class SolKeywordCompletion : SolCompletionTestBase() {

  fun testRootCompletion() = checkCompletion(
    hashSetOf(
      "pragma solidity",
      "pragma ",
      "library ",
      "contract ",
      "interface ",
      "abstract ",
      "enum ",
      "struct ",
      "event ",
      "error ",
      "using ",
      "type ",
      "import "
    ) + elementaryType, """
      /*caret*/
  """
  )

  fun testKeywordOnContractCompletion() = checkCompletion(
    hashSetOf(
      "layout "
    ), """
      contract A /*caret*/
  """
  )

  fun testKeywordInContractCompletion() = checkCompletion(
    contractBodyElement, """
      contract A{
        /*caret*/
      }
  """
  )

  fun testKeywordInInterfaceCompletion() = checkCompletion(
    contractBodyElement, """
      interface A{
        /*caret*/
      }
  """
  )

  fun testKeywordInLibraryCompletion() = checkCompletion(
    contractBodyElement, """
      library A{
        /*caret*/
      }
  """
  )

  fun testInFunctionKeywords() = checkCompletion(
    hashSetOf(
      "this",
      "return",
      "while",
      "assembly",
      "assert",
      "require",
      "revert",
      "super",
      "if",
      "else",
      "delete",
      "payable",
      "new",
      "do",
      "continue",
      "try",
      "catch",
      "emit"
    ), """
        contract A {
            function test() {
                /*caret*/
            }
        } 
  """
  )

  fun testOnFunctionKeywords() = checkCompletion(
    hashSetOf("external ", "internal ", "public ", "private ", "virtual ", "override", "returns") + stateMutability, """
        contract A {
            function test() /*caret*/ 
        } 
  """
  )

  fun testDataLocationKeywords() = checkCompletion(
    dataLocation, """
        contract A {
            function test(uint256 /*caret*/
        } 
  """
  )

  fun testImportFromKeyword() = checkCompletion(
    hashSetOf("from "), """
        import { x } /*caret*/
  """
  )

  fun testThisKeywordInMemberAccess() = checkCompletion(
    hashSetOf("this"), """
        contract A {
            address a;
            
            function test() {
                a.transfer(/*caret*/);
            }
        } 
  """
  )

  fun testKeywordsVariable() = checkCompletion(
    hashSetOf("constant ", "internal ", "public ", "private ", "override ", "immutable ", "transient "), """
        contract A{
            uint256 /*caret*/
        }
        
  """
  )

  fun testKeywordOnConstructorCompletion() = checkCompletion(
    hashSetOf("payable ", "internal ", "public "), """
      contract A{
        constructor()/*caret*/
      }
  """
  )

  fun testKeywordOnEventCompletion() = checkCompletion(
    hashSetOf("indexed "), """
      event /*caret*/
      
  """
  )

  fun testKeywordOnUsingCompletion() = checkCompletion(
    hashSetOf("for "), """
      using test /*caret*/
      
  """
  )

  fun testKeywordOnUsingForCompletion() = checkCompletion(
    hashSetOf("global "), """
      using test for uint256 /*caret*/
      
  """
  )

  fun testKeywordOnModifierCompletion() = checkCompletion(
    hashSetOf("override ", "virtual "), """
      contract A{
        modifier test() /*caret*/
       
      }
  """
  )

  fun testKeywordOnFallbackCompletion() = checkCompletion(
    hashSetOf("external ", "virtual ", "override ") + stateMutability, """
      contract A{
        fallback() /*caret*/
       
      }
  """
  )

  fun testKeywordOnReceiveCompletion() = checkCompletion(
    hashSetOf("external ", "virtual ", "override ", "payable "), """
      contract A{
        receive() /*caret*/
       
      }
  """
  )


  fun testThisKeywordNotInMemberAccess() = checkCompletion(
    hashSetOf("call", "code", "delegatecall", "transfer", "balance", "codehash", "send", "staticcall"), """
        contract A {
            address a;
        
            function test() {
                a./*caret*/
            }
        } 
  """, strict = false
  )

  fun testBreakInWhile() = checkCompletion(
    hashSetOf("break"), """
    contract A {
      function example() {
        while(true) {
        /*caret*/
        }
      }
    }
  """
  )

  fun testBreakInFor() = checkCompletion(
    hashSetOf("break"), """
    contract A {
      function example() {
        for(uint256 i = 0; i < 1000; i++) {
        /*caret*/
        }
      }
    }
  """
  )

  fun testPayableAddressTypeCompletions() = checkCompletion(
    hashSetOf("payable"), """
     contract A {
      address /*caret*/
     }
  """
  )

  fun testKeywordDoesNotAddSecondSpace() = checkResultAfterCompletion(
    "contract A { function f() public { uint25/*caret*/ x; } }",
    "contract A { function f() public { uint256<caret> x; } }"
  )

  fun testKeywordDoesNotAddSpace() = checkResultAfterCompletion(
    "contract A { function f() public { uint25/*caret*/} }",
    "contract A { function f() public { uint256<caret>} }"
  )

  fun testStatementKeywordsInsideBody() = checkCompletion(hashSetOf("for", "unchecked", "true", "false"), """
    contract A { function f() public { /*caret*/ } }
  """)

  fun testNoVisibilityKeywordsInsideBody() {
    InlineFile("contract A { function f() public { pu/*caret*/ } }").withCaret()
    val lookups = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse(lookups.toString(), "public" in lookups || "pure" in lookups)
  }
}
