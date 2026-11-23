package me.serce.solidity.lang.core

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.testFramework.LexerTestCase
import me.serce.solidity.lang.core.SolidityTokenTypes.*

class SolidityLexerTest : LexerTestCase() {
  override fun createLexer(): Lexer = SolidityLexer()

  override fun getDirPath() = ""

  private val names = mapOf(
    IDENTIFIER to "IDENTIFIER", DAYS to "DAYS", FUNCTION to "FUNCTION", IF to "IF", RETURN to "RETURN",
    ADDRESS to "ADDRESS", BYTENUMTYPE to "BYTENUMTYPE", UINTNUMTYPE to "UINTNUMTYPE", ASSEMBLY to "ASSEMBLY", RBRACE to "RBRACE"
  )

  /** "TYPE text" for each token, with TYPE the token's constant name for the types above. */
  private fun tokens(text: String, lexer: Lexer = createLexer()): List<String> {
    lexer.start(text)
    val result = mutableListOf<String>()
    while (true) {
      val type = lexer.tokenType ?: break
      if (type != TokenType.WHITE_SPACE) result += "${names[type] ?: type} ${lexer.tokenText}"
      lexer.advance()
    }
    return result
  }

  private val assembly = """
    contract C {
      uint days;
      function f() public {
        assembly "evmasm" ("memory-safe") { // comment {
          let memory := days /* } */
          function type(new) -> payable { if 1 { leave } }
          return(address(), byte(0, 1))
        }
        uint256 weeks = 1 days;
      }
    }
  """.trimIndent()

  fun testSolidityKeywordsAreNamesInAssembly() {
    val tokens = tokens(assembly)
    for (name in listOf("memory", "type", "new", "payable", "leave", "let")) {
      assertTrue(name, "IDENTIFIER $name" in tokens)
    }
    // the second `days` is a Yul name, the third a Solidity unit after the block
    assertEquals(listOf("DAYS days", "IDENTIFIER days", "DAYS days"), tokens.filter { it.endsWith(" days") })
    for (kept in listOf("FUNCTION function", "IF if", "RETURN return", "ADDRESS address", "BYTENUMTYPE byte", "UINTNUMTYPE uint256")) {
      assertTrue(kept, kept in tokens)
    }
  }

  fun testUnfinishedAssemblyHeader() {
    assertEquals(listOf("ASSEMBLY assembly", "RBRACE }", "UINTNUMTYPE uint", "DAYS days"), tokens("assembly } uint days"))
  }

  fun testYulFile() {
    val tokens = tokens("object \"A\" { code { let days := memory } } // x", SolidityLexer(yul = true))
    assertTrue("IDENTIFIER days" in tokens)
    assertTrue("IDENTIFIER memory" in tokens)
  }

  fun testRestart() {
    checkCorrectRestart(assembly)
  }

  fun testRestartYulFile() {
    val text = "{ let days := 1 /* c */ { function type() {} } }\n// end"
    val lexer = SolidityLexer(yul = true)
    lexer.start(text)
    while (lexer.tokenType != null) {
      assertEquals("every Yul token is a restart point", 0, lexer.state)
      if (lexer.tokenType == COMMENT) break
      lexer.advance()
    }
  }
}
