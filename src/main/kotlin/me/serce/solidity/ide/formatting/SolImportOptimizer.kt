package me.serce.solidity.ide.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.core.SolidityTokenTypes
import com.intellij.psi.util.descendantsOfType
import me.serce.solidity.ide.inspections.fixes.ImportFileAction
import me.serce.solidity.lang.SolidityFileType
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.types.outerContract

class SolImportOptimizer : ImportOptimizer {
  override fun supports(file: PsiFile) = file.fileType == SolidityFileType

  override fun processFile(file: PsiFile): Runnable = processFile(file, true)

  fun processFile(file: PsiFile, fullOptimization: Boolean): Runnable {
    val list = file.descendantsOfType<SolImportDirective>().toList().takeIf { it.isNotEmpty() }
    return when {
      list == null || !fullOptimization && list.size <= 1 -> Runnable {}
      fullOptimization -> processFull(file, list)
      else -> processLight(file, list)
    }
  }

  private fun processFull(file: PsiFile, list: List<SolImportDirective>): Runnable {
    val addSpecificSymbols = CodeStyle.getSettings(file).solidityCustomSettings.specificSymbolImports
    val (usedNames, unqualifiedNames) = identifiersOutsideImports(file)
    // aliased imports can't be rebuilt from the names they bring in: keep them, minus the aliases nobody uses
    val solFactory = SolPsiFactory(file.project)
    val aliased = list.filter { it.isAliased() }.mapNotNull { pruneAliased(it, usedNames, solFactory) }
    val imported = SolResolver.collectImportedNames(file).mapNotNull { name ->
      name.target.let { if (it is SolUserDefinedTypeName) SolResolver.resolveTypeNameUsingImports(it).firstOrNull() else it }
    }
    // each declaration with the name it is imported by: a member of a contract (except functions) by the contract's
    val namedDeclarations = imported.mapNotNull { declaration ->
      val contract = declaration.outerContract()
      when {
        contract == null -> declaration to declaration.name
        declaration is SolFunctionDefinition -> null
        else -> declaration to contract.name
      }
    }
    val imports = namedDeclarations
      // a name only used through an alias (`Base`, `Lib.L`) needs no import of its own
      .filter { (_, name) -> name in unqualifiedNames }
      .groupBy({ it.first.containingFile }, { it.second })
      .mapNotNull { (declaringFile, names) ->
        val from = declaringFile?.virtualFile ?: return@mapNotNull null
        val symbols = if (addSpecificSymbols) names.filterNotNull().distinct() else emptyList()
        ImportFileAction.createImport(solFactory, symbols, from, file.virtualFile)
      }

    return Runnable { replaceImports(list, imports, aliased) }
  }

  private fun SolImportDirective.isAliased() = importAlias != null || importAliasedPairList.any { it.importAlias != null }

  /**
   * [directive] without the names [used] doesn't contain, as a new directive, or null when none of them is used.
   * `import "x" as X` and `import * as X from "x"` are kept whole when `X` is used.
   */
  private fun pruneAliased(directive: SolImportDirective, used: Set<String>, factory: SolPsiFactory): SolImportDirective? {
    directive.importAlias?.let { alias -> return if (alias.text in used) directive.copy() as SolImportDirective else null }
    val pairs = directive.importAliasedPairList
    val kept = pairs.filter { pair -> (pair.importAlias?.text ?: pair.userDefinedTypeName.text) in used }
    if (kept.isEmpty()) return null
    if (kept.size == pairs.size) return directive.copy() as SolImportDirective
    val path = directive.importPath?.text ?: return directive.copy() as SolImportDirective
    return factory.createImportDirective("{${kept.joinToString(", ") { it.text }}} from $path", addQuotes = false)
  }

  /** The identifiers used outside import directives: all of them, and those not after a `.` (not `X.name`). */
  private fun identifiersOutsideImports(file: PsiFile): Pair<Set<String>, Set<String>> {
    val all = HashSet<String>()
    val unqualified = HashSet<String>()
    file.accept(object : PsiRecursiveElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element is SolImportDirective) return
        if (element.node.elementType == SolidityTokenTypes.IDENTIFIER) {
          all += element.text
          val previous = PsiTreeUtil.prevVisibleLeaf(element)
          if (previous?.text != ".") unqualified += element.text
        }
        super.visitElement(element)
      }
    })
    return all to unqualified
  }

  /** Replaces the directives in [list] with [imports] and [aliased], dropping the rebuilt ones nothing uses. */
  private fun replaceImports(list: List<SolImportDirective>, imports: List<SolImportDirective>, aliased: List<SolImportDirective>) {
    val first = list.first().containingFile.let { it.children.filterIsInstance<SolPragmaDirective>().firstOrNull() ?: it.firstChild }
    val parent = first.parent
    list.forEach { it.delete() }
    val added = (imports + aliased).sortedBy { it.importPath?.text }
      .reversed().map { parent.addAfter(it, first) as SolImportDirective }
    val rebuilt = added.filter { !it.isAliased() }
    rebuilt.reversed().filter { SolResolver.collectUsedElements(it).isEmpty() }.forEach { it.delete() }
    layoutImports(first.containingFile)
  }

  /** Sorts the directives by text, in place. */
  private fun processLight(file: PsiFile, list: List<SolImportDirective>): Runnable {
    // copies, since replacing one directive invalidates it as the source of another
    val sorted = list.sortedBy { it.text }.zip(list).map { (wanted, current) -> if (wanted != current) wanted.copy() else wanted }
    return Runnable {
      list.zip(sorted).forEach { (current, wanted) -> if (current != wanted) current.replace(wanted) }
      layoutImports(file)
    }
  }
}

/**
 * Puts imports on consecutive lines with a blank line around the block, the way forge fmt lays them out. Nothing
 * reformats PSI edits in Solidity files, so code that adds or removes imports calls this itself.
 */
fun layoutImports(file: PsiFile) {
  val imports = file.children.filterIsInstance<SolImportDirective>()
  if (imports.isEmpty()) return
  val facade = PsiParserFacade.getInstance(file.project)
  fun separate(before: PsiElement, after: PsiElement, lineBreaks: String) {
    val between = generateSequence(before.nextSibling) { it.nextSibling }.takeWhile { it != after }.toList()
    if (between.any { it !is PsiWhiteSpace }) return // a comment in between stays where it is
    // keep the indentation of the line the next element is on
    val indent = between.joinToString("") { it.text }.let { if ('\n' in it) it.substringAfterLast('\n') else "" }
    between.forEach { it.delete() }
    file.addAfter(facade.createWhiteSpaceFromText(lineBreaks + indent), before)
  }
  for (import in imports) {
    val previous = import.prevSiblingSkipping()
    if (previous == null) { // the file starts with it
      generateSequence(import.prevSibling) { it.prevSibling }.toList().forEach { it.delete() }
      continue
    }
    separate(previous, import, if (previous is SolImportDirective) "\n" else "\n\n")
  }
  val last = imports.last()
  last.nextSiblingSkipping()?.let { separate(last, it, "\n\n") }
}

private fun PsiElement.prevSiblingSkipping(): PsiElement? = generateSequence(prevSibling) { it.prevSibling }.firstOrNull { it !is PsiWhiteSpace }
private fun PsiElement.nextSiblingSkipping(): PsiElement? = generateSequence(nextSibling) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace }
