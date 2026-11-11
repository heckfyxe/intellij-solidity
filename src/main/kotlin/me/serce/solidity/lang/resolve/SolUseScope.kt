package me.serce.solidity.lang.resolve

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.SolidityFileType
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.psi.SolBlock
import me.serce.solidity.lang.psi.SolImportDirective
import me.serce.solidity.lang.psi.SolNamedElement
import me.serce.solidity.lang.psi.SolVariableDeclaration

/**
 * Where a Solidity declaration can be referenced from, so find usages and rename only look there instead of in every
 * file that mentions the name (in a project with dependencies, hundreds of library files say `owner`).
 */
object SolUseScope {

  /** null when the element is not in a physical file; callers then fall back to the platform's scope. */
  fun of(element: SolNamedElement): SearchScope? {
    // a local variable lives in its function or modifier body
    if (element is SolVariableDeclaration) {
      PsiTreeUtil.getTopmostParentOfType(element, SolBlock::class.java)?.let { return LocalSearchScope(it) }
    }
    val file = element.containingFile?.originalFile as? SolidityFile ?: return null
    if (file.virtualFile == null) return null
    return GlobalSearchScope.filesScope(element.project, importers(file))
  }

  /** [file] and every file that imports it, directly or through other imports: the only places its declarations are visible. */
  fun importers(file: SolidityFile): Set<VirtualFile> = CachedValuesManager.getCachedValue(file) {
    CachedValueProvider.Result.create(computeImporters(file), PsiModificationTracker.MODIFICATION_COUNT)
  }

  private fun computeImporters(file: SolidityFile): Set<VirtualFile> {
    val project = file.project
    val solFiles = GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(project), SolidityFileType)
    val helper = PsiSearchHelper.getInstance(project)
    val result = linkedSetOf(file.virtualFile)
    val queue = ArrayDeque<SolidityFile>().apply { add(file) }
    while (queue.isNotEmpty()) {
      val imported = queue.removeFirst()
      // An import's path names the file, so only files with that name in a string literal can import it.
      val word = searchWord(imported.virtualFile) ?: continue
      helper.processAllFilesWithWordInLiterals(word, solFiles) { candidate ->
        val vFile = candidate.virtualFile
        if (candidate is SolidityFile && vFile != null && vFile !in result && candidate.imports(imported)) {
          result += vFile
          queue += candidate
        }
        true
      }
    }
    return result
  }

  private fun SolidityFile.imports(other: SolidityFile): Boolean =
    PsiTreeUtil.getChildrenOfTypeAsList(this, SolImportDirective::class.java).any {
      it.importPath?.reference?.resolve()?.containingFile == other
    }

  private val nonIdentifierChars = Regex("[^A-Za-z0-9_$]+")

  /** The word index splits literals at non-identifier characters, so search for the longest identifier-like part of the name. */
  private fun searchWord(file: VirtualFile): String? =
    file.nameWithoutExtension.split(nonIdentifierChars).maxByOrNull { it.length }?.takeIf { it.isNotEmpty() }
}
