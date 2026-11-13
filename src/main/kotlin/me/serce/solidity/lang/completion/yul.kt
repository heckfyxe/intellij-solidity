package me.serce.solidity.lang.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolYulResolver
import me.serce.solidity.lang.resolve.SolYulResolver.parameters
import me.serce.solidity.lang.resolve.YulBuiltins

val YUL_KEYWORDS = arrayOf("let", "if", "switch", "case", "default", "for", "function", "true", "false")

/** Whether [element] is inside inline assembly or a Yul file. */
fun isInsideYul(element: PsiElement): Boolean =
  PsiTreeUtil.getParentOfType(element, SolYulBlock::class.java, SolBlock::class.java) is SolYulBlock

/**
 * Completion inside inline assembly and Yul files: Yul variables and functions in scope, built-ins, the Solidity
 * variables assembly can refer to and Yul keywords — nothing else from Solidity.
 */
class SolYulCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, psiElement(SolidityTokenTypes.IDENTIFIER).inside(SolYulBlock::class.java), YulCompletionProvider)
  }

  private object YulCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val position = parameters.position
      result.stopHere()
      if (PsiTreeUtil.prevVisibleLeaf(position)?.elementType == SolidityTokenTypes.DOT) {
        // `x.slot`, `x.offset` of storage variables, `x.offset`, `x.length` of calldata ones
        listOf("slot", "offset", "length").forEach { result.addElement(LookupElementBuilder.create(it).keywordPrioritised()) }
        return
      }
      // a name being declared
      val parent = position.parent
      if (parent is SolYulVariable || parent is SolYulFunctionDefinition) return

      val scope = SolYulResolver.scope(position)
      scope.variables.distinctBy { it.name }.forEach { variable ->
        val name = variable.name ?: return@forEach
        val kind = if (variable.parent is SolYulFunctionDefinition) "parameter" else "let"
        result.addElement(prioritized(LookupElementBuilder.create(variable, name).withIcon(SolidityIcons.STATE_VAR).withTypeText(kind), 30.0))
      }
      val declared = scope.variables.mapNotNullTo(hashSetOf()) { it.name }
      scope.solidity.filter { it.name !in declared }.distinctBy { it.name }.forEach { variable ->
        val name = variable.name ?: return@forEach
        val icon = when (variable) {
          is SolStateVariableDeclaration -> SolidityIcons.STATE_VAR
          is SolConstantVariable -> SolidityIcons.CONSTANT_VARIABLE
          else -> SolidityIcons.STATE_VAR
        }
        result.addElement(prioritized(LookupElementBuilder.create(variable, name).withIcon(icon).withTypeText("Solidity"), 20.0))
      }
      scope.functions.distinctBy { it.name }.forEach { function ->
        val name = function.name ?: return@forEach
        val params = function.parameters.map { it.name ?: "_" }
        result.addElement(prioritized(callLookup(LookupElementBuilder.create(function, name), params), 10.0))
      }
      val functionNames = scope.functions.mapNotNullTo(hashSetOf()) { it.name }
      YulBuiltins.all.filter { it.name !in functionNames }.forEach { builtin ->
        val lookup = LookupElementBuilder.create(builtin.name).bold().withStrikeoutness(builtin.deprecated)
          .withTypeText(if (builtin.returns) "builtin" else "builtin, no value")
        result.addElement(prioritized(callLookup(lookup, builtin.params), 0.0))
      }
      yulKeywords(position).forEach { result.addElement(LookupElementBuilder.create(it).bold().keywordPrioritised()) }
    }

    private fun yulKeywords(position: PsiElement): List<String> {
      val keywords = YUL_KEYWORDS.toMutableList()
      var prev: PsiElement = position
      var current = position.parent
      var inLoopBody = false
      var inFunction = false
      while (current != null && current !is SolInlineAssemblyStatement) {
        if (current is SolYulFunctionDefinition) {
          inFunction = true
          break // loops outside a function don't continue into it
        }
        if (current is SolYulForStatement && prev is SolYulBlock && current.yulBlockList.indexOf(prev) == 2) inLoopBody = true
        prev = current
        current = current.parent
      }
      if (inLoopBody) keywords += listOf("break", "continue")
      if (inFunction) keywords += "leave"
      return keywords
    }

    private fun prioritized(lookup: LookupElementBuilder, priority: Double): LookupElement =
      PrioritizedLookupElement.withPriority(lookup, priority)

    /** Shows `(a, b)` and inserts `()`, with the caret inside when the function takes arguments. */
    private fun callLookup(lookup: LookupElementBuilder, params: List<String>): LookupElementBuilder =
      lookup.withTailText(params.joinToString(", ", "(", ")"), true).withInsertHandler { ctx, _ ->
        val text = ctx.document.charsSequence
        val tail = ctx.tailOffset
        if (tail < text.length && text[tail] == '(') {
          ctx.editor.caretModel.moveToOffset(tail + 1)
        } else {
          ctx.document.insertString(tail, "()")
          ctx.editor.caretModel.moveToOffset(if (params.isEmpty()) tail + 2 else tail + 1)
        }
      }
  }
}
