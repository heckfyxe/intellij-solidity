package me.serce.solidity.ide

import com.intellij.psi.tree.TokenSet
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import me.serce.solidity.ide.colors.SolColor
import me.serce.solidity.lang.core.SolidityLexer
import me.serce.solidity.lang.core.SolidityTokenTypes.*

class SolHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
  override fun createHighlighter() = SolHighlighter
}

class YulHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
  override fun createHighlighter() = YulHighlighter
}

/** `.yul` files: the same colors, with the lexer in Yul mode. */
object YulHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = SolidityLexer(yul = true)

  override fun getTokenHighlights(tokenType: IElementType): Array<out TextAttributesKey> = SolHighlighter.getTokenHighlights(tokenType)
}

object SolHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = SolidityLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<out TextAttributesKey> {
    return pack(tokenMapping[tokenType])
  }

  // lazy: the token sets below are initialised after it
  private val tokenMapping: Map<IElementType, TextAttributesKey> by lazy {
    mapOf(
    COMMENT to SolColor.LINE_COMMENT,
    NAT_SPEC_TAG to SolColor.NAT_SPEC_TAG,

    LBRACE to SolColor.BRACES,
    RBRACE to SolColor.BRACES,
    LBRACKET to SolColor.BRACKETS,
    RBRACKET to SolColor.BRACKETS,
    LPAREN to SolColor.PARENTHESES,
    RPAREN to SolColor.PARENTHESES,
    SEMICOLON to SolColor.SEMICOLON,

    SCIENTIFICNUMBER to SolColor.NUMBER,
    FIXEDNUMBER to SolColor.NUMBER,
    DECIMALNUMBER to SolColor.NUMBER,
    HEXNUMBER to SolColor.NUMBER,

    STRINGLITERAL to SolColor.STRING,
    HEXLITERAL to SolColor.STRING
  ).plus(
    keywords.types.map { it to SolColor.KEYWORD }
  ).plus(
    literals.types.map { it to SolColor.KEYWORD }
  ).plus(
    operators.types.map { it to SolColor.OPERATION_SIGN }
  ).plus(
    types.types.map { it to SolColor.TYPE }
  ).mapValues { it.value.textAttributesKey }
  }

  val keywords: TokenSet = TokenSet.create(
    // Note, the ERROR/REVERT are not keywords and are excluded
    IMPORT, AS, PRAGMA, NEW, DELETE, EMIT, /*REVERT,*/ CONSTRUCTOR,
    CONTRACT, LIBRARY, INTERFACE, ABSTRACT, IS, STRUCT, FUNCTION, ENUM,
    PUBLIC, PRIVATE, INTERNAL, EXTERNAL, CONSTANT, PURE, VIEW,
    IF, ELSE, FOR, WHILE, DO, BREAK, CONTINUE, THROW, USING, RETURN, RETURNS, TRY, CATCH,
    MAPPING, EVENT, /*ERROR,*/ ANONYMOUS, MODIFIER, ASSEMBLY,
    VAR, STORAGE, MEMORY, CALLDATA, WEI, ETHER, GWEI, SZABO, FINNEY, SECONDS, MINUTES, HOURS,
    DAYS, WEEKS, YEARS, TYPE, VIRTUAL, OVERRIDE, IMMUTABLE, INDEXED, UNCHECKED,
    PAYABLE, FALLBACK, RECEIVE
  )

  val types: TokenSet = TokenSet.create(
    BYTENUMTYPE, BYTESNUMTYPE, FIXEDNUMTYPE, INTNUMTYPE, UFIXEDNUMTYPE, UINTNUMTYPE,
    STRING, BOOL, ADDRESS,
  )

  private val literals = TokenSet.create(BOOLEANLITERAL)

  private val operators = TokenSet.create(
    NOT, ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, MULT_ASSIGN, DIV_ASSIGN, PERCENT_ASSIGN,
    OR_ASSIGN, XOR_ASSIGN, AND_ASSIGN, LSHIFT_ASSIGN, RSHIFT_ASSIGN, QUESTION,
    PLUS, MINUS, MULT, DIV, EXPONENT, CARET,
    LESS, MORE, LESSEQ, MOREEQ,
    AND, ANDAND, OR, OROR,
    EQ, NEQ, TO,
    INC, DEC,
    TILDE, PERCENT,
    LSHIFT, RSHIFT,
    LEFT_ASSEMBLY, RIGHT_ASSEMBLY
  )
}

