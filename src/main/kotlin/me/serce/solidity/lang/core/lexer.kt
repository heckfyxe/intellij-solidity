package me.serce.solidity.lang.core

import com.intellij.lexer.FlexAdapter
import me.serce.solidity._SolidityLexer
import java.io.Reader

class SolidityLexer : FlexAdapter(_SolidityLexer(null as Reader?))
