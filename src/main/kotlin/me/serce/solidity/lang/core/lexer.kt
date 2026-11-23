package me.serce.solidity.lang.core

import com.intellij.lexer.FlexAdapter
import me.serce.solidity._SolidityLexer
import java.io.Reader

/**
 * The Solidity lexer; with [yul], the lexer of `.yul` files, which lexes everything as the inside of an assembly block.
 * Its Yul state is reported as state 0 so that highlighting can restart anywhere in a `.yul` file.
 */
class SolidityLexer(private val yul: Boolean = false) : FlexAdapter(_SolidityLexer(null as Reader?)) {
  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    super.start(buffer, startOffset, endOffset, if (yul && initialState == _SolidityLexer.YYINITIAL) _SolidityLexer.YUL_FILE else initialState)
  }

  override fun getState(): Int = super.getState().let { if (yul && it == _SolidityLexer.YUL_FILE) _SolidityLexer.YYINITIAL else it }
}
