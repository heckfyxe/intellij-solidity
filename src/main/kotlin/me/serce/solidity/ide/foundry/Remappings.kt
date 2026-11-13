package me.serce.solidity.ide.foundry

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import me.serce.solidity.chain.foundry.FoundryProject
import java.nio.file.Path

/**
 * forge's `remappings.txt`: one `[context:]prefix=target` per line, targets relative to the Foundry root (the file's
 * directory).
 */
object RemappingsFile {
  const val NAME = "remappings.txt"

  fun isRemappingsFile(file: PsiFile): Boolean = file.originalFile.name == NAME

  /** The Foundry root a remappings file belongs to: its own directory. */
  fun root(file: PsiFile): VirtualFile? = file.originalFile.virtualFile?.parent

  /** The library directories forge looks in (`libs`), `lib` until forge's config is known. */
  fun libDirs(root: VirtualFile): List<VirtualFile> {
    val libs = FoundryProject(Path.of(root.path), "").cachedConfig()?.getOrNull()?.libs ?: listOf("lib")
    return libs.mapNotNull { root.findFileByRelativePath(it.trim('/')) }.filter { it.isDirectory }
  }

  /**
   * A remapping for each library: `name/=lib/name/src/` (or `contracts/`, or the library root), named after the
   * source directory's package.json when it has one, e.g. `@openzeppelin/contracts/=lib/openzeppelin-contracts/contracts/`.
   */
  fun suggestedRemappings(root: VirtualFile): List<String> = libDirs(root).flatMap { libs ->
    libs.children.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }.map { lib ->
      val sourceDir = listOf("src", "contracts").firstOrNull { lib.findChild(it)?.isDirectory == true }
      val target = VfsUtilCore.getRelativePath(lib, root, '/') + "/" + (sourceDir?.let { "$it/" } ?: "")
      val prefix = sourceDir?.let { lib.findChild(it) }?.let { packageName(it) } ?: lib.name
      "$prefix/=$target"
    }
  }

  private fun packageName(dir: VirtualFile): String? {
    val json = dir.findChild("package.json") ?: return null
    return runCatching { ObjectMapper().readTree(VfsUtilCore.loadText(json))["name"]?.asText() }.getOrNull()?.takeIf { it.isNotBlank() }
  }
}

/** Completes whole remappings for the libraries before `=`, and directories after it. */
class RemappingsCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val file = parameters.originalFile
    if (!RemappingsFile.isRemappingsFile(file)) return
    val root = RemappingsFile.root(file) ?: return
    val text = parameters.editor.document.charsSequence
    val offset = parameters.offset
    var lineStart = offset
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    val typed = text.subSequence(lineStart, offset).toString()
    val eq = typed.indexOf('=')
    if (eq < 0) {
      val existing = text.lines().mapNotNull { line -> line.substringBefore('=', "").trim().takeIf { it.isNotEmpty() } }.toSet()
      val known = FoundryProject(Path.of(root.path), "").cachedConfig()?.getOrNull()?.remappings.orEmpty()
      val suggestions = (RemappingsFile.suggestedRemappings(root) + known).distinctBy { it.substringBefore('=') }
        .filter { it.substringBefore('=') !in existing }
      val matcher = result.withPrefixMatcher(typed.trimStart())
      suggestions.forEach { matcher.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Folder)) }
      result.stopHere()
      return
    }
    val path = typed.substring(eq + 1).trimStart()
    val parentPath = path.substringBeforeLast('/', "")
    val dir = (if (parentPath.isEmpty()) root else root.findFileByRelativePath(parentPath)) ?: return
    val matcher = result.withPrefixMatcher(path)
    dir.children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach { child ->
      val full = (if (parentPath.isEmpty()) "" else "$parentPath/") + child.name + "/"
      matcher.addElement(LookupElementBuilder.create(full).withPresentableText(child.name + "/").withIcon(AllIcons.Nodes.Folder))
    }
    result.stopHere()
  }
}

/** Remappings without `=`, and targets that don't exist relative to the Foundry root. */
class RemappingsInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (!RemappingsFile.isRemappingsFile(file)) return null
    val root = RemappingsFile.root(file) ?: return null
    val problems = mutableListOf<ProblemDescriptor>()
    var lineStart = 0
    for (line in file.text.split('\n')) {
      val content = line.trimEnd('\r')
      val trimmed = content.trim()
      if (trimmed.isNotEmpty()) {
        val start = lineStart + content.indexOf(trimmed)
        val eq = trimmed.indexOf('=')
        if (eq <= 0) {
          problems += manager.createProblemDescriptor(
            file, TextRange(start, start + trimmed.length), "A remapping has the form [context:]prefix=target",
            ProblemHighlightType.GENERIC_ERROR, isOnTheFly
          )
        } else {
          val target = trimmed.substring(eq + 1).trim()
          val path = target.trimEnd('/')
          val exists = path.isEmpty() || Path.of(path).isAbsolute && java.io.File(path).exists() ||
            root.findFileByRelativePath(path) != null
          if (!exists) {
            val targetStart = start + trimmed.indexOf(target, eq + 1)
            problems += manager.createProblemDescriptor(
              file, TextRange(targetStart, targetStart + target.length), "Remapping target '$target' does not exist",
              ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly
            )
          }
        }
      }
      lineStart += line.length + 1
    }
    return problems.toTypedArray()
  }
}
