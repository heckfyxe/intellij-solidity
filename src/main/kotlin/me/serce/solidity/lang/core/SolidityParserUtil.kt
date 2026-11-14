package me.serce.solidity.lang.core

import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.psi.TokenType

/**
 * External rules of the generated parser (`<<rule>>` in solidity.bnf).
 */
@Suppress("UNUSED_PARAMETER")
object SolidityParserUtil : GeneratedParserUtilBase() {
  /**
   * True when a line break (or the start of the file) separates the current token from the previous one.
   * Error recovery stops there, so a statement missing its `;` doesn't swallow the next line.
   */
  @JvmStatic
  fun afterNewLine(b: PsiBuilder, level: Int): Boolean {
    if (b.eof()) return false
    b.tokenType // skips whitespace and comments before the current token
    var i = -1
    while (true) {
      val type = b.rawLookup(i) ?: return true
      if (type != TokenType.WHITE_SPACE && type != SolidityTokenTypes.COMMENT && type != SolidityTokenTypes.NAT_SPEC_TAG) {
        return false
      }
      val start = b.rawTokenTypeStart(i)
      val end = b.rawTokenTypeStart(i + 1)
      if (b.originalText.subSequence(start, end).any { it == '\n' || it == '\r' }) return true
      i--
    }
  }
}
