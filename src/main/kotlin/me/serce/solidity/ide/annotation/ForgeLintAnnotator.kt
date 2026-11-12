package me.serce.solidity.ide.annotation

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.ide.BrowserUtil
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import me.serce.solidity.chain.Offsets
import me.serce.solidity.chain.foundry.FoundryProject

/** One `forge lint` diagnostic, with char offsets into the file's text. */
class ForgeLintIssue(
  val start: Int,
  val end: Int,
  val code: String,
  val message: String,
  val severity: HighlightSeverity,
  val helpUrl: String?,
)

/**
 * Shows `forge lint` diagnostics. forge reads the file from disk, so lint runs on the saved file; while the document
 * has unsaved changes, the last results stay attached to their code (via range markers) until the next save.
 * The project's `[lint]` settings in foundry.toml decide which lints run.
 */
class ForgeLintAnnotator : ExternalAnnotator<ForgeLintAnnotator.Input, ForgeLintAnnotator.Result>() {

  class Input(val foundry: FoundryProject?, val forge: String, val relativePath: String?, val text: String, val document: Document)
  class Result(val document: Document, val issues: List<ForgeLintIssue>?)

  override fun getPairedBatchInspectionShortName() = ForgeLintInspection.SHORT_NAME

  override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
    if (ApplicationManager.getApplication().isUnitTestMode) return null
    val vFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
    val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    val foundry = FoundryProject.find(file.project, vFile) ?: return null
    val relative = foundry.relativePath(vFile) ?: return null
    if (LIB_DIRS.any { relative.startsWith(it) }) return null // dependencies aren't yours to fix
    // unsaved: forge would lint stale content, so reuse the last results
    val forge = FoundryProject.forgePath(file.project)
    if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) return Input(null, forge, null, document.text, document)
    return Input(foundry, forge, relative, document.text, document)
  }

  override fun doAnnotate(input: Input): Result {
    val foundry = input.foundry ?: return Result(input.document, null)
    val issues = try {
      val output = foundry.exec(listOf(input.forge, "lint", input.relativePath!!, "--json"), timeoutMs = 30_000)
      parse(output.stdout + "\n" + output.stderr, input.relativePath, input.text)
    } catch (e: Exception) { // no forge, an old forge without `lint`, a bad foundry.toml: no lint results
      LOG.debug(e)
      emptyList()
    }
    return Result(input.document, issues)
  }

  override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
    val document = result.document
    val issues = result.issues
    val current: List<Pair<RangeMarker, ForgeLintIssue>> = if (issues != null) {
      // fresh results: remember them for edits made before the next save
      document.getUserData(CACHE)?.forEach { it.first.dispose() }
      val length = document.textLength
      issues.map { issue ->
        val start = issue.start.coerceIn(0, length)
        document.createRangeMarker(start, issue.end.coerceIn(start, length)) to issue
      }.also { document.putUserData(CACHE, it) }
    } else {
      document.getUserData(CACHE).orEmpty()
    }

    val length = file.textLength
    for ((marker, issue) in current) {
      if (!marker.isValid) continue
      val start = marker.startOffset.coerceIn(0, length)
      var end = marker.endOffset.coerceIn(start, length)
      if (end == start) end = minOf(length, start + 1)
      if (start >= end) continue
      val builder = holder.newAnnotation(issue.severity, "${issue.message} (forge lint: ${issue.code})")
        .range(TextRange(start, end))
        .tooltip(StringUtil.escapeXmlEntities("${issue.message}\nforge lint: ${issue.code}"))
        .withFix(SuppressForgeLintFix(issue.code))
      issue.helpUrl?.let { builder.withFix(OpenLintDocsFix(issue.code, it)) }
      builder.create()
    }
  }

  companion object {
    private val LOG = logger<ForgeLintAnnotator>()
    private val mapper = ObjectMapper()
    private val CACHE = Key.create<List<Pair<RangeMarker, ForgeLintIssue>>>("solidity.forge.lint.results")
    private val LIB_DIRS = listOf("lib/", "dependencies/", "node_modules/")

    /**
     * Parses `forge lint --json` output: one diagnostic per line, `{"code": {"code": "<lint>"}, "level": "warning",
     * "spans": [{"file_name", "byte_start", "byte_end", ...}], "children": [{"message": "<docs url>"}]}`.
     * Compiler errors (no lint code) and other files' diagnostics are skipped.
     */
    fun parse(output: String, relativePath: String, text: String): List<ForgeLintIssue> =
      output.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
        val json = runCatching { mapper.readTree(line) }.getOrNull() ?: return@mapNotNull null
        val code = json["code"]?.get("code")?.asText()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val span = json["spans"]?.firstOrNull { it["is_primary"]?.asBoolean() != false } ?: return@mapNotNull null
        if (span["file_name"]?.asText()?.removePrefix("./") != relativePath) return@mapNotNull null
        val severity = when (json["level"]?.asText()) {
          "error" -> HighlightSeverity.ERROR
          "warning" -> HighlightSeverity.WARNING
          else -> HighlightSeverity.WEAK_WARNING
        }
        val helpUrl = json["children"]?.map { it["message"]?.asText().orEmpty() }?.firstOrNull { it.startsWith("http") }
        ForgeLintIssue(
          start = Offsets.byteToChar(text, span["byte_start"]?.asInt() ?: return@mapNotNull null),
          end = Offsets.byteToChar(text, span["byte_end"]?.asInt() ?: return@mapNotNull null),
          code = code,
          message = json["message"]?.asText().orEmpty(),
          severity = severity,
          helpUrl = helpUrl,
        )
      }.toList()
  }
}

/** Lets forge lint be switched off (and its results listed) like any inspection. */
class ForgeLintInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
  override fun getShortName() = SHORT_NAME

  companion object {
    const val SHORT_NAME = "ForgeLint"
  }
}

/** Adds `// forge-lint: disable-next-line(<lint>)` above the line, with the same indentation. */
class SuppressForgeLintFix(private val code: String) : IntentionAction, LowPriorityAction {
  override fun getText() = "Suppress '$code' for this line"
  override fun getFamilyName() = "Suppress forge lint"
  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = editor != null
  override fun startInWriteAction() = true

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    editor ?: return
    val document = editor.document
    val line = document.getLineNumber(editor.caretModel.offset)
    val lineStart = document.getLineStartOffset(line)
    val lineText = document.charsSequence.subSequence(lineStart, document.getLineEndOffset(line))
    val indent = lineText.takeWhile { it == ' ' || it == '\t' }
    document.insertString(lineStart, "$indent// forge-lint: disable-next-line($code)\n")
  }
}

class OpenLintDocsFix(private val code: String, private val url: String) : IntentionAction, LowPriorityAction {
  override fun getText() = "Open documentation for '$code'"
  override fun getFamilyName() = "Open forge lint documentation"
  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
  override fun startInWriteAction() = false
  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = BrowserUtil.browse(url)
}

/** forge lints what is on disk, so a save brings new results: re-run the annotators of the saved file. */
class ForgeLintOnSaveListener : FileDocumentManagerListener {
  // there is no after-save callback: saving finishes within the current EDT event, so look after it
  override fun beforeDocumentSaving(document: Document) {
    val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.extension == "sol" } ?: return
    ApplicationManager.getApplication().invokeLater {
      for (project in ProjectLocator.getInstance().getProjectsForFile(file).filterNotNull()) {
        if (project.isDisposed) continue
        PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
      }
    }
  }
}
