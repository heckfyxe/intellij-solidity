package me.serce.solidity.lang.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver

/**
 * Completion while declaring and emitting events:
 * - `event E(|` offers types, `event E(uint256 |` offers `indexed` (while fewer than the allowed number are indexed),
 *   `event E(uint256 a) |` offers `anonymous`;
 * - `emit |` offers the events visible at the caret and the contracts whose events can be emitted as `emit C.E()`;
 *   `emit C.|` offers the events of `C`. Choosing an event inserts `E(|);`, or `E();|` when it has no parameters.
 *
 * Registered first so that it can stop the generic expression completion where only an event name is valid.
 */
class SolEventCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, psiElement(SolidityTokenTypes.IDENTIFIER).with(condition("emit name") { emitQualifier(it) == "" }), EmitNameProvider)
    extend(CompletionType.BASIC, psiElement(SolidityTokenTypes.IDENTIFIER).with(condition("emit member") { !emitQualifier(it).isNullOrEmpty() }), EmitMemberProvider)
    extend(
      CompletionType.BASIC,
      psiElement(SolidityTokenTypes.IDENTIFIER).withParent(SolUserDefinedTypeName::class.java).withSuperParent(2, SolIndexedParamDef::class.java),
      SolKeywordCompletionProvider(*KEYWORD_TYPE)
    )
    extend(CompletionType.BASIC, psiElement(SolidityTokenTypes.IDENTIFIER).withParent(SolIndexedParamDef::class.java), IndexedProvider)
    extend(CompletionType.BASIC, psiElement().with(condition("after event parameters") { eventBeforeTail(it) != null }), AnonymousProvider)
  }

  private object EmitNameProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val position = parameters.position
      val declarations = SolResolver.lexicalDeclarations(position).toList()
      val events = declarations.filterIsInstance<SolEventDefinition>()
      events.distinctBy { it.name }.forEach { event -> event.toEmitLookup()?.let(result::addElement) }

      // contracts whose events are not visible unqualified: `emit IERC20.Transfer(...)`
      val current = PsiTreeUtil.getParentOfType(position, SolContractDefinition::class.java)
      val inScope = current?.let { setOf(it) + it.superContracts() }.orEmpty()
      declarations.filterIsInstance<SolContractDefinition>()
        .filter { it !in inScope && it.events().isNotEmpty() }
        .distinctBy { it.name }
        .forEach { contract -> contract.toQualifierLookup()?.let(result::addElement) }
      result.stopHere()
    }
  }

  private object EmitMemberProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val position = parameters.position
      val qualifier = emitQualifier(position) ?: return
      val contract = SolResolver.lexicalDeclarations(position)
        .filterIsInstance<SolContractDefinition>()
        .firstOrNull { it.name == qualifier } ?: return
      contract.events().distinctBy { it.name }.forEach { event -> event.toEmitLookup()?.let(result::addElement) }
      result.stopHere()
    }
  }

  private object IndexedProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val param = parameters.position.parent as? SolIndexedParamDef ?: return
      if (param.node.findChildByType(SolidityTokenTypes.INDEXED) != null) return
      val event = param.parent?.parent as? SolEventDefinition ?: return
      // the copy may have lost a trailing `anonymous` to the completion marker's suffix, so look at the original
      val original = parameters.originalPosition?.let { PsiTreeUtil.getParentOfType(it, SolEventDefinition::class.java) } ?: event
      val limit = if (original.isAnonymous()) 4 else 3 // anonymous events have no selector topic
      val indexed = event.indexedParameterList?.indexedParamDefList.orEmpty()
        .count { it.node.findChildByType(SolidityTokenTypes.INDEXED) != null }
      if (indexed >= limit) return
      result.addElement(LookupElementBuilder.create("indexed").bold().keywordPrioritised())
    }
  }

  private object AnonymousProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val event = eventBeforeTail(parameters.position) ?: return
      if (event.isAnonymous()) return
      result.addElement(LookupElementBuilder.create("anonymous").bold())
      result.stopHere()
    }
  }
}

private fun condition(name: String, accepts: (PsiElement) -> Boolean) = object : PatternCondition<PsiElement>(name) {
  override fun accepts(t: PsiElement, context: ProcessingContext?) = accepts(t)
}

/**
 * For an identifier right after `emit`, returns "" for `emit |` and the contract's name for `emit C.|`;
 * null anywhere else.
 */
private fun emitQualifier(position: PsiElement): String? {
  val prev = PsiTreeUtil.prevVisibleLeaf(position) ?: return null
  if (prev.elementType == SolidityTokenTypes.EMIT) return ""
  if (prev.elementType != SolidityTokenTypes.DOT) return null
  val qualifier = PsiTreeUtil.prevVisibleLeaf(prev)?.takeIf { it.elementType == SolidityTokenTypes.IDENTIFIER } ?: return null
  return qualifier.text.takeIf { PsiTreeUtil.prevVisibleLeaf(qualifier)?.elementType == SolidityTokenTypes.EMIT }
}

/** The event whose parameter list closes right before [position] on the same line, as in `event E(uint a) |`. */
private fun eventBeforeTail(position: PsiElement): SolEventDefinition? {
  val prev = PsiTreeUtil.prevVisibleLeaf(position) ?: return null
  if (prev.elementType != SolidityTokenTypes.RPAREN) return null
  val event = (prev.parent as? SolIndexedParameterList)?.parent as? SolEventDefinition ?: return null
  val between = position.containingFile.viewProvider.contents.subSequence(prev.textRange.endOffset, position.textRange.startOffset)
  return event.takeUnless { between.contains('\n') }
}

private fun SolEventDefinition.isAnonymous() = node.findChildByType(SolidityTokenTypes.ANONYMOUS) != null

private fun SolContractDefinition.superContracts(): List<SolContractDefinition> =
  collectSupers.mapNotNull { it.reference?.resolve() as? SolContractDefinition }

/** Events that can be emitted as `emit C.E()`: those of the contract and of its bases. */
private fun SolContractDefinition.events(): List<SolEventDefinition> =
  eventDefinitionList + superContracts().flatMap { it.eventDefinitionList }

private fun SolEventDefinition.toEmitLookup(): LookupElement? {
  val name = name ?: return null
  val params = indexedParameterList?.indexedParamDefList.orEmpty()
  val tail = params.joinToString(", ", "(", ")") { it.text.replace(WHITESPACE, " ") }
  val builder = LookupElementBuilder.create(this, name)
    .withIcon(SolidityIcons.EVENT)
    .withTailText(tail, true)
    .withTypeText(PsiTreeUtil.getParentOfType(this, SolContractDefinition::class.java)?.name)
    .withInsertHandler(EmitInsertHandler(params.isNotEmpty()))
  return PrioritizedLookupElement.withPriority(builder, TYPED_COMPLETION_PRIORITY)
}

private fun SolContractDefinition.toQualifierLookup(): LookupElement? {
  val name = name ?: return null
  return LookupElementBuilder.create(this, name)
    .withIcon(getIcon(0))
    .withTailText(".", true)
    .withInsertHandler { ctx, _ ->
      val tail = ctx.tailOffset
      val text = ctx.document.charsSequence
      if (tail < text.length && text[tail] == '.') {
        ctx.editor.caretModel.moveToOffset(tail + 1)
      } else {
        ctx.document.insertString(tail, ".")
        ctx.editor.caretModel.moveToOffset(tail + 1)
      }
      AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
    }
}

/** Inserts `(|);`, or `();|` for an event without parameters; steps into parentheses that are already there. */
private class EmitInsertHandler(private val hasParams: Boolean) : InsertHandler<LookupElement> {
  override fun handleInsert(ctx: InsertionContext, item: LookupElement) {
    if (ctx.completionChar == '(') ctx.setAddCompletionChar(false)
    val document = ctx.document
    val caret = ctx.editor.caretModel
    val text = document.charsSequence
    var next = ctx.tailOffset
    while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
    if (next < text.length && text[next] == '(') {
      caret.moveToOffset(next + 1)
    } else {
      val tail = ctx.tailOffset
      val semicolonFollows = next < text.length && text[next] == ';'
      document.insertString(tail, if (semicolonFollows) "()" else "();")
      caret.moveToOffset(
        when {
          hasParams -> tail + 1
          semicolonFollows -> next + 3 // past the `()` and the `;` that was already there
          else -> tail + 3
        }
      )
    }
    if (hasParams) AutoPopupController.getInstance(ctx.project).autoPopupParameterInfo(ctx.editor, item.psiElement)
  }
}

private val WHITESPACE = Regex("\\s+")
