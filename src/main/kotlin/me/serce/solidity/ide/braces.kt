package me.serce.solidity.ide

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import me.serce.solidity.lang.core.SolidityTokenTypes.*

class SolBraceMatcher : PairedBraceMatcher {

  override fun getPairs() = PAIRS

  /** Like other languages, close a bracket automatically only where no code directly follows it. */
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
    contextType == null || contextType in ALLOWED_BEFORE

  override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset

  companion object {
    private val ALLOWED_BEFORE = setOf(
      TokenType.WHITE_SPACE, COMMENT, SEMICOLON, COMMA, RPAREN, RBRACKET, RBRACE, LBRACE, DOT
    )

    private val PAIRS = arrayOf(
      BracePair(LBRACE, RBRACE, false),
      BracePair(LBRACKET, RBRACKET, false),
      BracePair(LPAREN, RPAREN, false)
    )
  }
}
