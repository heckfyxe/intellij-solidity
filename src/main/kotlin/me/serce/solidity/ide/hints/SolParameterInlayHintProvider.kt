package me.serce.solidity.ide.hints

import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import me.serce.solidity.lang.psi.SolDeclarationItem
import me.serce.solidity.lang.psi.SolFunctionCallArguments
import me.serce.solidity.lang.psi.SolFunctionCallElement
import me.serce.solidity.lang.psi.SolMapExpression
import me.serce.solidity.lang.types.inferDeclType

class SolParameterInlayHintProvider : InlayParameterHintsProvider {

  override fun getParameterHints(element: PsiElement): List<InlayInfo> {
    return HintType.entries.filter { it.isApplicable(element) }.flatMap { it.provideHints(element) }
  }

  override fun getDefaultBlackList(): Set<String> {
    return emptySet()
  }
}

enum class HintType {
  TUPLE_HINT {
    override fun provideHints(e: PsiElement): List<InlayInfo> {
      val item = e as? SolDeclarationItem ?: return emptyList()
      return listOf(InlayInfo(inferDeclType(item).toString(), e.textOffset))
    }

    override fun isApplicable(e: PsiElement): Boolean = e is SolDeclarationItem
  },

  PARAMETER_HINT {
    override fun provideHints(e: PsiElement): List<InlayInfo> {
      val callElement = e.parentOfType<SolFunctionCallElement>(true) ?: return emptyList()
      return provideArgumentNameHints(callElement)
    }

    override fun isApplicable(e: PsiElement): Boolean = e is SolFunctionCallArguments
  };

  abstract fun isApplicable(e: PsiElement): Boolean
  abstract fun provideHints(e: PsiElement): List<InlayInfo>
}

fun provideArgumentNameHints(element: SolFunctionCallElement): List<InlayInfo> {
  val args = element.functionCallArguments.expressionList
  // Named arguments already show their parameter names in the source.
  if (args.firstOrNull() is SolMapExpression) return emptyList()

  val params = element.resolveDefinitions()?.singleOrNull()?.parseParameters() ?: return emptyList()
  return params.zip(args).map { (param, arg) -> InlayInfo(param.first ?: param.second.toString(), arg.startOffset) }
}
