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

  /**
   * Adds `';'` to the tokens expected at the current position without consuming anything, so a declaration or
   * statement followed by junk (`address a private;`) reports `';' expected` before recovery skips the junk.
   */
  @JvmStatic
  fun semicolonExpected(b: PsiBuilder, level: Int): Boolean {
    addVariant(b, ";")
    return true
  }

  /** After `function`: `receive`/`fallback` are the function's name, remapped to `Identifier`. Always succeeds. */
  @JvmStatic
  fun keywordFunctionName(b: PsiBuilder, level: Int): Boolean {
    val type = b.tokenType
    if (type == SolidityTokenTypes.RECEIVE || type == SolidityTokenTypes.FALLBACK) {
      b.remapCurrentToken(SolidityTokenTypes.IDENTIFIER)
    }
    return true
  }

  /**
   * `szabo`/`finney` after a number literal: removed in Solidity 0.7.0, so they are lexed as identifiers (a variable
   * may be called `szabo`), but code for older compilers still uses them as units. The token is remapped to
   * `SZABO`/`FINNEY` so the unit keeps its PSI and highlighting.
   */
  @JvmStatic
  fun oldDenomination(b: PsiBuilder, level: Int): Boolean {
    if (b.tokenType != SolidityTokenTypes.IDENTIFIER) return false
    val type = when (b.tokenText) {
      "szabo" -> SolidityTokenTypes.SZABO
      "finney" -> SolidityTokenTypes.FINNEY
      else -> return false
    }
    b.remapCurrentToken(type)
    b.advanceLexer()
    return true
  }

  /**
   * The call after `emit`/`revert`: an expression that must be a function call. A rule reference to
   * `FunctionCallExpression` would accept any expression (Grammar-Kit parses the whole precedence level), so
   * `emit x;` parsed without an error; here a non-call keeps its PSI and gets solc's `'(' expected`.
   */
  @JvmStatic
  fun callExpression(b: PsiBuilder, level: Int): Boolean {
    if (!me.serce.solidity.SolidityParser.Expression(b, level + 1, -1)) return false
    if (b.latestDoneMarker?.tokenType != SolidityTokenTypes.FUNCTION_CALL_EXPRESSION) {
      b.error(if (b.eof()) "'(' expected, unexpected end of file" else "'(' expected, got '${b.tokenText}'")
    }
    return true
  }
}
