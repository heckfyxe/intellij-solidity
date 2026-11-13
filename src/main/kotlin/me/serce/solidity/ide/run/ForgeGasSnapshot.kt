package me.serce.solidity.ide.run

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition

/** One line of forge's `.gas-snapshot`: `CounterTest:test_Increment() (gas: 51847)`. */
data class GasSnapshotEntry(val contract: String, val function: String, val parameters: String, val measurement: String) {
  /** The number of top-level parameters in the signature, e.g. 2 for `f(uint256,(uint8,address))`. */
  val parameterCount: Int
    get() {
      if (parameters.isBlank()) return 0
      var depth = 0
      return 1 + parameters.count { c ->
        when (c) {
          '(' -> depth++
          ')' -> depth--
        }
        c == ',' && depth == 0
      }
    }
}

object GasSnapshot {
  const val FILE_NAME = ".gas-snapshot"

  // the arguments can contain tuples, so match up to the last `) (`
  private val LINE = Regex("""^([^:\s]+):([A-Za-z_$][\w$]*)\((.*)\) \(([^()]*)\)$""")

  /**
   * Parses a snapshot. The measurement is kept as forge wrote it: `gas: 51847` for unit tests,
   * `runs: 256, μ: 50109, ~: 52511` for fuzz tests and `runs: 256, calls: 128000, reverts: 0` for invariants.
   */
  fun parse(text: String): List<GasSnapshotEntry> = text.lineSequence().mapNotNull { line ->
    LINE.matchEntire(line.trim())?.destructured?.let { (contract, function, params, measurement) ->
      GasSnapshotEntry(contract, function, params, measurement.trim())
    }
  }.toList()

  /** The entry for a test function; overloads are told apart by their parameter count. */
  fun find(entries: List<GasSnapshotEntry>, contract: String, function: String, parameterCount: Int): GasSnapshotEntry? {
    val candidates = entries.filter { it.contract == contract && it.function == function }
    return candidates.singleOrNull() ?: candidates.singleOrNull { it.parameterCount == parameterCount }
  }

  private val CACHE = Key.create<Pair<Long, List<GasSnapshotEntry>>>("solidity.gas.snapshot")

  /** The parsed `.gas-snapshot` of [file]'s Foundry project, re-read when the snapshot's modification stamp changes. */
  fun entriesFor(file: VirtualFile): List<GasSnapshotEntry>? {
    val snapshot = FoundryProject.rootOf(file)?.findChild(FILE_NAME)?.takeIf { it.isValid && !it.isDirectory } ?: return null
    val stamp = snapshot.modificationStamp
    snapshot.getUserData(CACHE)?.let { (s, entries) -> if (s == stamp) return entries }
    val entries = runCatching { parse(VfsUtilCore.loadText(snapshot)) }.getOrDefault(emptyList())
    snapshot.putUserData(CACHE, stamp to entries)
    return entries
  }
}

/** Shows the gas forge recorded in `.gas-snapshot` at the end of each test function's header line. */
class ForgeGasSnapshotInlayProvider : InlayHintsProvider {
  override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
    val vFile = file.originalFile.virtualFile ?: return null
    if (!isForgeTestFile(file)) return null
    val entries = GasSnapshot.entriesFor(vFile)?.takeIf { it.isNotEmpty() } ?: return null
    val document = editor.document
    return object : SharedBypassCollector {
      override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        val function = element as? SolFunctionDefinition ?: return
        if (!function.isForgeTest()) return
        val name = function.name ?: return
        val contract = function.parentOfType<SolContractDefinition>()?.name ?: return
        val entry = GasSnapshot.find(entries, contract, name, function.parameters.size) ?: return
        val anchor = function.identifier ?: return
        val line = document.getLineNumber(anchor.textRange.startOffset)
        sink.addPresentation(EndOfLinePosition(line), tooltip = "From ${GasSnapshot.FILE_NAME}", hasBackground = true) {
          text(entry.measurement)
        }
      }
    }
  }
}

/** Redraws the hints when forge rewrites a `.gas-snapshot`. */
class GasSnapshotChangeListener(private val project: Project) : BulkFileListener {
  override fun after(events: List<VFileEvent>) {
    if (events.any { it.path.endsWith("/" + GasSnapshot.FILE_NAME) || it.path == GasSnapshot.FILE_NAME }) {
      if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
    }
  }
}
