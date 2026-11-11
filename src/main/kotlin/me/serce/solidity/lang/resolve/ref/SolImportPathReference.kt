package me.serce.solidity.lang.resolve.ref

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.tree.LeafElement
import me.serce.solidity.ide.inspections.fixes.ImportFileAction
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.psi.impl.SolImportPathElement

class SolImportPathReference(element: SolImportPathElement) : SolReferenceBase<SolImportPathElement>(element) {
  override fun singleResolve(): PsiElement? {
    val importText = element.text
    if (importText.length < 2) {
      return null
    }
    val containingFile = element.containingFile?.originalFile?.virtualFile
    if (containingFile == null || !containingFile.isValid) {
      return null
    }
    val path = importText.substring(1, importText.length - 1)
    return findImportFile(element.project, containingFile, path)
      ?.let { PsiManager.getInstance(element.project).findFile(it) }
  }

  companion object {
    fun findImportFile(project: Project, file: VirtualFile, path: String): VirtualFile? {
      if (!file.isValid) {
        return null
      }
      val directFile = file.findFileByRelativePath("../$path")
      val resolved = directFile
        ?: findFoundryRemapping(project, file, path)
        ?: findNpmImportFile(file, path)
        ?: findEthPMImportFile(file, path)
        ?: findFoundryImportFile(project, file, path)
      return resolved?.takeIf { it.isValid }
    }

    private fun findFoundryRemapping(project: Project, file: VirtualFile, path: String): VirtualFile? {
      return SolImportConfigService.getInstance(project).resolveRemapping(path, file)
    }

    private fun findNpmImportFile(file: VirtualFile, path: String): VirtualFile? =
      findInSelfOrAncestors(file, "node_modules/$path")

    private fun findFoundryImportFile(project: Project, file: VirtualFile, path: String): VirtualFile? {
      return SolImportConfigService.getInstance(project).resolve(path, file)
    }

    private fun findEthPMImportFile(file: VirtualFile, path: String): VirtualFile? =
      findInSelfOrAncestors(file, "installed_contracts/" + path.replaceFirst("/", "/contracts/"))

    /** [relativePath] looked up from [file], then from each valid ancestor directory, nearest first. */
    private fun findInSelfOrAncestors(file: VirtualFile, relativePath: String): VirtualFile? =
      generateSequence(file) { it.parent?.takeIf { parent -> parent.isValid } }
        .firstNotNullOfOrNull { it.findFileByRelativePath(relativePath) }
  }

  override fun doRename(identifier: PsiElement, newName: String) {
    if (identifier !is LeafElement) {
      return
    }
    val renamedElement = resolve()
    if (renamedElement !is SolidityFile) {
      return
    }
    val name = renamedElement.name
    val newImportPath = (identifier as PsiElement).text.replace(name, newName)
    identifier.replaceWithText(newImportPath)
  }

  override fun bindToElement(element: PsiElement): PsiElement {
    val file = element as? SolidityFile ?: return element
    val newPath =
      ImportFileAction.buildImportPath(element.project, this.element.containingFile.virtualFile, file.virtualFile)
    val identifier = this.element.referenceNameElement as? LeafElement ?: return element
    return identifier.replaceWithText("\"$newPath\"").psi ?: element
  }
}
