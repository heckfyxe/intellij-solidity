package me.serce.solidity.ide.run

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.Visibility
import com.intellij.psi.util.childrenOfType

/** Prefixes of the functions forge runs as tests: unit and fuzz tests, invariants and table tests. */
private val TEST_PREFIXES = listOf("test", "invariant", "statefulFuzz", "table")

/** A function forge runs as a test: a public or external function whose name starts with one of [TEST_PREFIXES]. */
fun SolFunctionDefinition.isForgeTest(): Boolean {
  val name = name ?: return false
  return TEST_PREFIXES.any { name.startsWith(it) } &&
    (visibility == null || visibility == Visibility.PUBLIC || visibility == Visibility.EXTERNAL)
}

fun SolContractDefinition.hasForgeTests(): Boolean = functionDefinitionList.any { it.isForgeTest() }

/** A `*.t.sol` file, or a Solidity file in the `test` directory of its Foundry project. */
fun isForgeTestFile(file: PsiFile): Boolean {
  if (file.name.endsWith(".t.sol")) return true
  val vf = file.virtualFile ?: return false
  val root = FoundryProject.rootOf(vf) ?: return false
  return VfsUtilCore.getRelativePath(vf, root)?.startsWith("test/") == true
}

/** Where forge runs for [element]: the nearest directory with a `foundry.toml`, else the project's base directory. */
fun forgeWorkingDirectory(element: PsiElement): String =
  element.containingFile?.virtualFile?.let { FoundryProject.rootOf(it) }?.path ?: element.project.basePath ?: ""

/** Navigates from the test tree to the test contract or function: `solidity-test://<file>::<Contract>[::<function>]`. */
object ForgeTestLocator : SMTestLocator {
  const val PROTOCOL = "solidity-test"

  fun hint(path: String, contract: String, function: String? = null): String =
    "$PROTOCOL://$path::$contract" + (function?.let { "::$it" } ?: "")

  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != PROTOCOL) return emptyList()
    val parts = path.split("::")
    val vf = LocalFileSystem.getInstance().findFileByPath(parts[0]) ?: return emptyList()
    val file = PsiManager.getInstance(project).findFile(vf) ?: return emptyList()
    val contract = parts.getOrNull(1)?.let { name -> file.childrenOfType<SolContractDefinition>().firstOrNull { it.name == name } }
    val function = parts.getOrNull(2)?.let { name -> contract?.functionDefinitionList?.firstOrNull { it.name == name } }
    return listOf(PsiLocation(function ?: contract ?: file))
  }
}

/** Links `test/A.t.sol:8:33` (compiler messages, traces) in forge's output to the file, relative to [root]. */
class SolidityFileLocationFilter(private val project: Project, private val root: com.intellij.openapi.vfs.VirtualFile?) : com.intellij.execution.filters.Filter {
  private val location = Regex("""((?:[\w.@-]+/)*[\w.@-]+\.sol):(\d+)(?::(\d+))?""")

  override fun applyFilter(line: String, entireLength: Int): com.intellij.execution.filters.Filter.Result? {
    val lineStart = entireLength - line.length
    val items = location.findAll(line).mapNotNull { m ->
      val path = m.groupValues[1]
      val file = (if (path.startsWith("/")) LocalFileSystem.getInstance().findFileByPath(path) else root?.findFileByRelativePath(path))
        ?: return@mapNotNull null
      val lineNumber = m.groupValues[2].toInt() - 1
      val column = m.groupValues[3].toIntOrNull()?.minus(1) ?: 0
      com.intellij.execution.filters.Filter.ResultItem(
        lineStart + m.range.first, lineStart + m.range.last + 1,
        com.intellij.execution.filters.OpenFileHyperlinkInfo(project, file, lineNumber, column)
      )
    }.toList()
    return if (items.isEmpty()) null else com.intellij.execution.filters.Filter.Result(items)
  }
}
