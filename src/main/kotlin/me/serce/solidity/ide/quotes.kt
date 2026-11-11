package me.serce.solidity.ide

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import me.serce.solidity.lang.core.SolidityTokenTypes

// The lexer keeps an unterminated string as a STRINGLITERAL up to the end of the line, which lets the default
// non-closed-literal check decide when typing a quote should insert the closing one.
class SolQuoteTokenHandler : SimpleTokenSetQuoteHandler(SolidityTokenTypes.STRINGLITERAL)
