package me.serce.solidity.chain.compile

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import me.serce.solidity.chain.ChainService
import me.serce.solidity.chain.Offsets
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.foundry.SolcLocator
import java.io.File

/**
 * Shows solc errors and warnings as you type. Runs solc in analysis-only mode (no bytecode) on the
 * current file, including unsaved changes, with the same solc version and remappings forge uses.
 */
class SolcExternalAnnotator : ExternalAnnotator<SolcExternalAnnotator.Input, List<SolcExternalAnnotator.Problem>>() {

  class Input(val foundry: FoundryProject, val relativePath: String, val text: String, val unsaved: Map<String, String>)
  class Problem(val start: Int, val end: Int, val severity: HighlightSeverity, val message: String, val tooltip: String)

  override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
    if (!ChainService.getInstance(file.project).compileWhileEditing) return null
    val vFile = file.virtualFile ?: return null
    val foundry = FoundryProject.find(file.project, vFile) ?: return null
    val relative = foundry.relativePath(vFile) ?: return null
    val fdm = FileDocumentManager.getInstance()
    val unsaved = fdm.unsavedDocuments.mapNotNull { doc ->
      val f = fdm.getFile(doc)?.takeIf { it != vFile && it.extension == "sol" } ?: return@mapNotNull null
      foundry.relativePath(f)?.let { it to doc.text }
    }.toMap()
    return Input(foundry, relative, file.text, unsaved)
  }

  override fun doAnnotate(input: Input): List<Problem> {
    return try {
      val config = input.foundry.config()
      val solc = SolcLocator.find(config.solc, input.text) ?: return emptyList()
      val solcVersion = SolcRequest.versionOf(solc.path)
      val root = input.foundry.root.toFile()
      val allowed = (listOf(root) + config.libs.map { File(root, it) }).joinToString(",") { it.path }
      val command = listOf(solc.path, "--standard-json", "--base-path", root.path, "--allow-paths", allowed)
      fun compile(withEvmVersion: Boolean): JsonNode? {
        val request = SolcRequest.build(input.relativePath, input.text, input.unsaved, config, solcVersion, withEvmVersion)
        val output = input.foundry.exec(command, timeoutMs = 15_000, stdin = mapper.writeValueAsBytes(request))
        return if (output.stdout.isBlank()) null else mapper.readTree(output.stdout)
      }
      var json = compile(withEvmVersion = true) ?: return emptyList()
      // a solc whose version we couldn't tell may reject the configured EVM version: fall back to its default
      if (json["errors"]?.any { it["type"]?.asText() == "JSONError" && it["message"]?.asText().orEmpty().contains("EVM version") } == true) {
        json = compile(withEvmVersion = false) ?: return emptyList()
      }
      json["errors"]?.mapNotNull { err ->
        val loc = err["sourceLocation"] ?: return@mapNotNull null
        if (loc["file"]?.asText() != input.relativePath) return@mapNotNull null
        val severity = when (err["severity"]?.asText()) {
          "error" -> HighlightSeverity.ERROR
          "warning" -> HighlightSeverity.WARNING
          else -> HighlightSeverity.WEAK_WARNING
        }
        val start = Offsets.byteToChar(input.text, loc["start"]?.asInt() ?: return@mapNotNull null)
        val end = Offsets.byteToChar(input.text, loc["end"]?.asInt() ?: return@mapNotNull null)
        val message = err["message"]?.asText().orEmpty()
        val type = err["type"]?.asText().orEmpty()
        Problem(start, end, severity, message, "$type: $message")
      } ?: emptyList()
    } catch (e: Exception) { // no forge, bad foundry.toml, solc timeout: just no annotations
      LOG.debug(e)
      emptyList()
    }
  }

  override fun apply(file: PsiFile, problems: List<Problem>, holder: AnnotationHolder) {
    val length = file.textLength
    for (p in problems) {
      val start = p.start.coerceIn(0, length)
      var end = p.end.coerceIn(start, length)
      if (end == start) end = minOf(length, start + 1)
      holder.newAnnotation(p.severity, p.message)
        .range(TextRange(start, end))
        .tooltip(StringUtil.escapeXmlEntities(p.tooltip))
        .create()
    }
  }

  companion object {
    private val LOG = logger<SolcExternalAnnotator>()
    private val mapper = ObjectMapper()
  }
}
