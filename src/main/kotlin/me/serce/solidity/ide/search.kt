package me.serce.solidity.ide

import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import me.serce.solidity.lang.core.SolidityLexer
import me.serce.solidity.lang.core.SolidityParserDefinition
import me.serce.solidity.lang.core.SolidityTokenTypes.IDENTIFIER
import me.serce.solidity.lang.core.SolidityTokenTypes.STRINGLITERAL
import me.serce.solidity.lang.psi.*

class SolFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = SolWordScanner()
  override fun canFindUsagesFor(element: PsiElement) = element is SolNamedElement

  override fun getHelpId(element: PsiElement) = HelpID.FIND_OTHER_USAGES
  override fun getType(element: PsiElement): String = when (element) {
    is SolContractDefinition -> element.contractType.docName
    is SolFunctionDefinition -> "function"
    is SolModifierDefinition -> "modifier"
    is SolEventDefinition -> "event"
    is SolErrorDefinition -> "error"
    is SolStructDefinition -> "struct"
    is SolEnumDefinition -> "enum"
    is SolEnumValue -> "enum value"
    is SolUserDefinedValueTypeDefinition -> "type"
    is SolStateVariableDeclaration -> "state variable"
    is SolConstantVariableDeclaration -> "constant"
    is SolParameterDef -> "parameter"
    is SolYulFunctionDefinition -> "Yul function"
    is SolYulVariable -> if (element.parent is SolYulFunctionDefinition) "Yul parameter" else "Yul variable"
    else -> "variable"
  }

  override fun getDescriptiveName(element: PsiElement): String = (element as? SolNamedElement)?.name ?: ""
  override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

class SolWordScanner : DefaultWordsScanner(
  SolidityLexer(),
  TokenSet.create(IDENTIFIER),
  SolidityParserDefinition.COMMENTS,
  TokenSet.create(STRINGLITERAL)
)
