package me.serce.solidity.ide.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFileFactory
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
    val allTypes = SolResolver.collectImportedNames(file).mapNotNull { it.target.let { if (it is SolUserDefinedTypeName) SolResolver.resolveTypeNameUsingImports(it).firstOrNull() else it } }
    val solFactory = SolPsiFactory(file.project)
    val imports = allTypes
      .mapNotNull {
        it.outerContract()?.let { parentContract ->
          if (it is SolFunctionDefinition) return@mapNotNull null
          return@mapNotNull Pair(it, parentContract.name)
        }
        Pair(it, it.name)
      }
      .groupBy { it.first.containingFile }
      .mapNotNull {
        val file1 = it.key.containingFile?.virtualFile ?: return@mapNotNull null
        val solUserDefinedTypeName = if (addSpecificSymbols) it.value.mapNotNull { it.second }.distinct() else emptyList()
        val to = file.virtualFile
        ImportFileAction.createImport(solFactory, solUserDefinedTypeName, file1, to)
      }
      .sortedBy { it.importPath?.text }

    return Runnable {
      extracted(list, imports)
    }
  }

  private fun extracted(list: List<SolImportDirective>, imports: List<SolImportDirective>) {
    val first = list.first().containingFile.let { it.children.filterIsInstance<SolPragmaDirective>().firstOrNull() ?: it.firstChild }
    val parent = first.parent
    list.forEach { it.delete() }
    imports.reversed().forEach { parent.addAfter(it, first) }
    parent.childrenOfType<SolImportDirective>().reversed().filter { SolResolver.collectUsedElements(it).isEmpty() }.forEach { it.delete() }
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
