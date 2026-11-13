package me.serce.solidity.ide.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.core.SolidityTokenTypes
import com.intellij.psi.util.childrenOfType
import com.intellij.psi.util.descendantsOfType
import me.serce.solidity.ide.inspections.fixes.ImportFileAction
import me.serce.solidity.lang.SolidityFileType
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.types.outerContract

class SolImportOptimizer : ImportOptimizer {
    override fun supports(file: PsiFile) = file.fileType == SolidityFileType

    override fun processFile(file: PsiFile): Runnable {
        return processFile(file, true)
    }

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
    val aliased = list.filter { it.isAliased() }.mapNotNull { pruneAliased(it, usedNames) }
    val allTypes = SolResolver.collectImportedNames(file)
      .mapNotNull { it.target.let { if (it is SolUserDefinedTypeName) SolResolver.resolveTypeNameUsingImports(it).firstOrNull() else it } }
    val solFactory = SolPsiFactory(file.project)
    val imports = allTypes
      .mapNotNull {
        it.outerContract()?.let { parentContract ->
          if (it is SolFunctionDefinition) return@mapNotNull null
          return@mapNotNull Pair(it, parentContract.name)
        }
        Pair(it, it.name)
      }
      // a name only used through an alias (`Base`, `Lib.L`) needs no import of its own
      .filter { (_, name) -> name in unqualifiedNames }
      .groupBy { it.first.containingFile }
      .mapNotNull {
        val file1 = it.key.containingFile?.virtualFile ?: return@mapNotNull null
        val solUserDefinedTypeName = if (addSpecificSymbols) it.value.mapNotNull { it.second }.distinct() else emptyList()
        val to = file.virtualFile
        ImportFileAction.createImport(solFactory, solUserDefinedTypeName, file1, to)
      }

    return Runnable {
      extracted(list, imports, aliased)
    }
  }

  private fun SolImportDirective.isAliased() = importAlias != null || importAliasedPairList.any { it.importAlias != null }

  /**
   * [directive] without the names [used] doesn't contain, as a new directive, or null when none of them is used.
   * `import "x" as X` and `import * as X from "x"` are kept whole when `X` is used.
   */
  private fun pruneAliased(directive: SolImportDirective, used: Set<String>): SolImportDirective? {
    directive.importAlias?.let { alias -> return if (alias.text in used) directive.copy() as SolImportDirective else null }
    val pairs = directive.importAliasedPairList
    val kept = pairs.filter { pair -> (pair.importAlias?.text ?: pair.userDefinedTypeName.text) in used }
    if (kept.isEmpty()) return null
    if (kept.size == pairs.size) return directive.copy() as SolImportDirective
    val path = directive.importPath?.text ?: return directive.copy() as SolImportDirective
    val text = "import {${kept.joinToString(", ") { it.text }}} from $path;"
    return PsiFileFactory.getInstance(directive.project).createFileFromText("DUMMY.sol", SolidityFileType, text).childOfType()
      ?: directive.copy() as SolImportDirective
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

  private fun extracted(list: List<SolImportDirective>, imports: List<SolImportDirective>, aliased: List<SolImportDirective>) {
    val first = list.first().containingFile.let { it.children.filterIsInstance<SolPragmaDirective>().firstOrNull() ?: it.firstChild }
    val parent = first.parent
    list.forEach { it.delete() }
    val added = (imports + aliased).sortedBy { it.importPath?.text }
      .reversed().map { parent.addAfter(it, first) as SolImportDirective }
    val rebuilt = added.filter { !it.isAliased() }
    rebuilt.reversed().filter { SolResolver.collectUsedElements(it).isEmpty() }.forEach { it.delete() }
    layoutImports(first.containingFile)
  }

  private fun processLight(file: PsiFile, list: List<SolImportDirective>): Runnable {
        val factory = PsiFileFactory.getInstance(file.project)

        val sorted = list.sortedBy { it.text }.zip(list).map {
            if (it.first != it.second) {
                factory.createFileFromText("DUMMY.tsol", SolidityFileType, it.first.text).childOfType() ?: it.first
            } else it.first
        }
        return Runnable {
            list.zip(sorted).forEach {
                if (it.first != it.second) {
                    it.first.replace(it.second)
                }
            }
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
