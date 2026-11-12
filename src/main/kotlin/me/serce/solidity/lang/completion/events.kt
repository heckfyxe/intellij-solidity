package me.serce.solidity.lang.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.ide.inspections.fixes.ImportFileAction
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.stubs.SolEventIndex

/**
 * Completion while declaring and emitting events:
 * - `event E(|` offers types, `event E(uint256 |` offers `indexed` (while fewer than the allowed number are indexed),
 *   `event E(uint256 a) |` offers `anonymous`;
 * - `emit |` offers the events visible at the caret and the contracts whose events can be emitted as `emit C.E()`;
 *   `emit C.|` offers the events of `C`. Choosing an event inserts `E(|);`, or `E();|` when it has no parameters;
 * - once something is typed (or on explicit completion), `emit` also offers events that are not visible yet: a
 *   file-level event is imported by name, an event of a contract is inserted as `C.E` and `C` imported if needed.
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

      // like project-wide types, not on the autopopup right after `emit `
      if (parameters.invocationCount > 0 || result.prefixMatcher.prefix.isNotEmpty()) {
        addEventsToImport(parameters, result, declarations, inScope)
      }
      result.stopHere()
    }

    /**
     * Events from the index that are not visible at the caret: a file-level event is imported by name, an event of a
     * contract is emitted qualified (`C.E`) with the contract imported when it is not visible yet.
     */
    private fun addEventsToImport(
      parameters: CompletionParameters,
      result: CompletionResultSet,
      declarations: List<SolNamedElement>,
      inScope: Set<SolContractDefinition>
    ) {
      val project = parameters.position.project
      val visibleEvents = declarations.filterIsInstance<SolEventDefinition>().mapTo(hashSetOf()) { it.key() }
      val visibleContracts = declarations.filterIsInstance<SolContractDefinition>().mapTo(hashSetOf()) { it.key() }
      val ownContracts = inScope.mapTo(hashSetOf()) { it.key() }
      val scope = GlobalSearchScope.allScope(project)
      StubIndex.getInstance().getAllKeys(SolEventIndex.KEY, project)
        .filter { result.prefixMatcher.prefixMatches(it) }
        .flatMap { StubIndex.getElements(SolEventIndex.KEY, it, project, scope, SolNamedElement::class.java).filterIsInstance<SolEventDefinition>() }
        .forEach { event ->
          if (event.key() in visibleEvents) return@forEach
          val owner = event.parent as? SolContractDefinition
          if (owner != null && owner.key() in ownContracts) return@forEach
          val import = when {
            owner == null -> event
            owner.key() in visibleContracts -> null
            else -> owner
          }
          event.toEmitLookup(qualifier = owner?.name, import = import)?.let(result::addElement)
        }
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

/** Identifies a declaration across the completion copy of the file and the original. */
private fun PsiNamedElement.key(): Triple<String, String?, String?> {
  val file = containingFile.originalFile
  return Triple(file.virtualFile?.path ?: file.name, (parent as? PsiNamedElement)?.name, name)
}

/**
 * [qualifier] is inserted before the name (`C.` for an event of contract `C`), and [import] (the event itself or its
 * contract) is imported by name from its file.
 */
private fun SolEventDefinition.toEmitLookup(qualifier: String? = null, import: SolNamedElement? = null): LookupElement? {
  val name = name ?: return null
  val params = indexedParameterList?.indexedParamDefList.orEmpty()
  val tail = params.joinToString(", ", "(", ")") { it.text.replace(WHITESPACE, " ") }
  var builder = LookupElementBuilder.create(this, name)
    .withIcon(SolidityIcons.EVENT)
    .withTailText(tail, true)
    .withTypeText(if (import != null) "from ${import.containingFile.name}" else PsiTreeUtil.getParentOfType(this, SolContractDefinition::class.java)?.name)
    .withInsertHandler(EmitInsertHandler(params.isNotEmpty(), qualifier, import))
  if (qualifier != null) builder = builder.withPresentableText("$qualifier.$name").withLookupString("$qualifier.$name")
  // events to import come after the visible ones
  return PrioritizedLookupElement.withPriority(builder, if (qualifier == null && import == null) TYPED_COMPLETION_PRIORITY else 0.0)
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

/**
 * Inserts `(|);`, or `();|` for an event without parameters; steps into parentheses that are already there.
 * Prepends [qualifier] and imports [import] when given.
 */
private class EmitInsertHandler(
  private val hasParams: Boolean,
  private val qualifier: String? = null,
  private val import: SolNamedElement? = null
) : InsertHandler<LookupElement> {
  override fun handleInsert(ctx: InsertionContext, item: LookupElement) {
    if (ctx.completionChar == '(') ctx.setAddCompletionChar(false)
    val document = ctx.document
    if (qualifier != null) document.insertString(ctx.startOffset, "$qualifier.")
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
    val import = import
    if (import != null && import.isValid) {
      PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
      ImportFileAction.addNamedImport(import, ctx.file)
    }
    if (hasParams) AutoPopupController.getInstance(ctx.project).autoPopupParameterInfo(ctx.editor, item.psiElement)
  }
}

private val WHITESPACE = Regex("\\s+")
