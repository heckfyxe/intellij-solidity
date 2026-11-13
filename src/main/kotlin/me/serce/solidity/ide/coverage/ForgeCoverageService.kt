package me.serce.solidity.ide.coverage

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/** Holds the last `forge coverage` result and paints it in the gutter of the source files' editors. */
@Service(Service.Level.PROJECT)
class ForgeCoverageService(private val project: Project) : Disposable {
  /** Absolute source path (VFS form, `/`-separated) to its coverage. */
  @Volatile
  private var coverage: Map<String, FileCoverage> = emptyMap()

  val isActive get() = coverage.isNotEmpty()

  init {
    EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
      override fun editorCreated(event: EditorFactoryEvent) {
        if (event.editor.project == project && isActive) apply(event.editor)
      }
    }, this)
  }

  /** Replaces the coverage with an lcov report whose `SF:` paths are relative to [root], then repaints open editors. */
  fun setReport(root: String, lcov: Map<String, FileCoverage>) {
    val base = root.replace('\\', '/').trimEnd('/')
    coverage = lcov.mapKeys { (path, _) -> if (path.startsWith("/") || path.matches(Regex("^[A-Za-z]:/.*"))) path else "$base/$path" }
    refreshEditors()
  }

  fun clear() {
    coverage = emptyMap()
    refreshEditors()
  }

  fun coverageFor(path: String): FileCoverage? = coverage[path]

  private fun refreshEditors() {
    val run = { EditorFactory.getInstance().allEditors.filter { it.project == project || it.project == null }.forEach { apply(it) } }
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) run() else app.invokeLater(run, project.disposed)
  }

  /** Removes this service's markers from [editor] and adds the current ones. */
  fun apply(editor: Editor) {
    editor.getUserData(HIGHLIGHTERS)?.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
    editor.putUserData(HIGHLIGHTERS, null)
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
    val fileCoverage = coverage[file.path] ?: return
    val document = editor.document
    val added = fileCoverage.states().mapNotNull { (line, state) ->
      val index = line - 1
      if (index < 0 || index >= document.lineCount) return@mapNotNull null
      editor.markupModel.addRangeHighlighter(
        document.getLineStartOffset(index), document.getLineEndOffset(index),
        HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE
      ).apply { lineMarkerRenderer = CoverageRenderer(state) }
    }
    editor.putUserData(HIGHLIGHTERS, added)
  }

  override fun dispose() {}

  private class CoverageRenderer(val state: LineCoverage) : LineMarkerRenderer {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
      g.color = state.color
      g.fillRect(r.x, r.y, JBUI.scale(3), r.height)
    }
  }

  companion object {
    val HIGHLIGHTERS = Key.create<List<RangeHighlighter>>("solidity.forge.coverage.highlighters")

    fun getInstance(project: Project): ForgeCoverageService = project.service()
  }
}

private val LineCoverage.color: Color
  get() = when (this) {
    LineCoverage.COVERED -> JBColor(Color(0x6CC57C), Color(0x4E8A5A))
    LineCoverage.PARTIAL -> JBColor(Color(0xE8C25A), Color(0xA8893A))
    LineCoverage.UNCOVERED -> JBColor(Color(0xE57373), Color(0xA04848))
  }
