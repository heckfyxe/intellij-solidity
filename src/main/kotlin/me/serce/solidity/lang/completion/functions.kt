package me.serce.solidity.lang.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.StandardPatterns.and
import com.intellij.patterns.StandardPatterns.not
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset
import com.intellij.psi.util.PsiTreeUtil.getParentOfType
import com.intellij.util.ProcessingContext
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.ide.hints.varargsText
import me.serce.solidity.lang.types.SolInternalTypeFactory
import me.serce.solidity.lang.types.SolType

class SolFunctionCompletionContributor : CompletionContributor(), DumbAware {

  init {
    extend(CompletionType.BASIC, and(expression(), not(mapExpression())), FunctionCompletionProvider)
  }

  override fun beforeCompletion(context: CompletionInitializationContext) {
    val file = context.file
    if (file is SolidityFile) {
      if (context.completionType == CompletionType.BASIC) {
        val dummySuffix = deduceSemicolonOrBracket(context.editor, file, context.startOffset)
        context.dummyIdentifier = CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED + dummySuffix
      }
    }
  }
}

private fun deduceSemicolonOrBracket(editor: Editor, file: SolidityFile, startOffset: Int): String {
  // Yul has no semicolons, and a closing parenthesis would break the statement being typed
  if (listOfNotNull(file.findElementAt(startOffset), file.findElementAt(maxOf(0, startOffset - 1))).any(::isInsideYul)) return ""
  if (isMemberAccessEndingLine(file, startOffset)) return ";"
  if (editor !is EditorEx) {
    return ""
  }

  val element = file.findElementAt(startOffset)

  val iterator = editor.highlighter.createIterator(startOffset)
  if (iterator.atEnd()) {
    return ""
  }

  if (iterator.tokenType === TokenType.WHITE_SPACE) {
    iterator.advance()
  }

  if (!iterator.atEnd() && iterator.tokenType === SolidityTokenTypes.IDENTIFIER) {
    iterator.advance()
  }

  if (!iterator.atEnd() && iterator.tokenType === SolidityTokenTypes.LPAREN
    && getParentOfType(element, SolFunctionCallExpression::class.java) != null
  ) {
    return "" // function call
  }

  if (!iterator.atEnd() && iterator.tokenType === SolidityTokenTypes.RPAREN) {
    iterator.advance()
  }

  if (!iterator.atEnd() && iterator.tokenType == SolidityTokenTypes.SEMICOLON) {
    return ""
  }

  // lets check where we are in the source code
  if (findElementOfClassAtOffset(file, startOffset, SolFunctionCallArguments::class.java, false) != null) {
    return ""
  }

  // it may be array access
  if (!iterator.atEnd() && iterator.tokenType === SolidityTokenTypes.RBRACKET) {
    return "];"
  }

  return ");"
}

/**
 * `source.` at the end of a line followed by another statement parses as a declaration of a variable of type
 * `source.<member>`, which completes type names instead of members. Ending the statement right after the completion
 * marker keeps it a member access, as it is when nothing follows.
 */
private fun isMemberAccessEndingLine(file: SolidityFile, offset: Int): Boolean {
  val text = file.viewProvider.contents
  var dot = offset
  while (dot > 0 && Character.isJavaIdentifierPart(text[dot - 1])) dot--
  if (dot < 2 || text[dot - 1] != '.') return false
  val beforeDot = text[dot - 2]
  if (!Character.isJavaIdentifierPart(beforeDot) && beforeDot != ')' && beforeDot != ']') return false
  var end = offset
  while (end < text.length && text[end] != '\n') {
    if (!text[end].isWhitespace()) return false
    end++
  }
  val element = file.findElementAt(dot - 1) ?: return false
  return getParentOfType(element, SolBlock::class.java, SolYulBlock::class.java) is SolBlock
}

object FunctionCompletionProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {
    // actual completion is performed via com.intellij.psi.PsiReference#getVariants
  }
}

fun SolCallable.toFunctionLookup(): LookupElementBuilder? {
  return getName()?.let {
    LookupElementBuilder
      .create(it)
      .withBoldness(true)
      .withIcon(SolidityIcons.FUNCTION)
      .withTypeText(funcOutType(this))
      .withTailText(funcInType(this))
      .insertParenthesis(false)
  }
}

fun SolStructDefinition.toStructLookup(): LookupElementBuilder? {
  return name?.let { LookupElementBuilder
      .create(this)
      .withBoldness(true)
      .withIcon(SolidityIcons.STRUCT)
      .withTypeText(funcOutType(this))
      .withTailText(funcInType(this))
      .insertParenthesis(false)
  }
}

fun SolNamedElement.toVarLookup(): LookupElementBuilder? {
  return name?.let {
    LookupElementBuilder.create(this).withIcon(SolidityIcons.STATE_VAR)
  }
}

private fun funcOutType(elem: SolCallable): String = elem.parseType().toString()

private fun funcInType(elem: SolCallable): String = elem.parseParameters().joinToString(prefix = "(", postfix = ")") { formatParam(it) }

private fun formatParam(param: Pair<String?, SolType>): String {
  return when (param.first) {
    null -> param.second.toString()
    SolInternalTypeFactory.varargsId -> varargsText(param.second)
    else -> "${param.first}: ${param.second}"
  }
}
