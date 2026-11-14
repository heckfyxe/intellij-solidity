package me.serce.solidity.ide.hints

import com.intellij.lang.documentation.DocumentationMarkup.*
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import me.serce.solidity.lang.core.SolidityTokenTypes

/**
 * NatSpec of a declaration, parsed from its comment tokens: text before the first tag is the `@notice`, as in solc.
 * [entries] keep source order; a tag's text runs until the next tag.
 */
class NatSpecDoc private constructor(val entries: List<Entry>) {
  /** [tag] without `@` (`notice`, `param`, `custom:x`); [text] has the comment markers and indentation removed. */
  class Entry(val tag: String, val text: String)

  /**
   * Description, then sections laid out like Java's (`JavaDocInfoGenerator`): params as `<code>name</code> &ndash; text`
   * lines. Tag texts are Markdown, rendered by the platform's [DocMarkdownToHtmlConverter].
   */
  fun toHtml(project: Project): String {
    val html = StringBuilder()
    val title: List<Entry> = tagged("title")
    val description: List<Entry> = entries.filter { it.tag == "notice" || it.tag == "dev" }
    if (title.isNotEmpty() || description.isNotEmpty()) {
      html.append(CONTENT_START)
      for (entry in title) html.append("<p><b>").append(markdown(project, entry.text)).append("</b>")
      for (entry in description) {
        val text: String = markdown(project, entry.text)
        html.append(if (text.startsWith("<p>")) text else "<p>$text")
      }
      html.append(CONTENT_END)
    }

    val sections = ArrayList<Section>()
    val params: List<Pair<String, String>> = tagged("param").map { named(it.text) }.distinctBy { it.first }
    if (params.isNotEmpty()) sections.add(Section("Params:", params.map { paramHtml(project, it.first, it.second) }))
    addSection(project, sections, "Returns:", tagged("return"))
    addSection(project, sections, "Author:", tagged("author"))
    val custom: Map<String, List<Entry>> =
      entries.filter { it.tag.startsWith("custom:") && "@${it.tag}" != NO_VALIDATION_TAG }.groupBy { it.tag }
    for ((tag, list) in custom) addSection(project, sections, escape(tag.removePrefix("custom:")) + ":", list)

    if (sections.isNotEmpty()) {
      html.append(SECTIONS_START)
      for (section in sections) {
        html.append(SECTION_HEADER_START).append(section.header).append(SECTION_SEPARATOR)
        html.append(section.values.joinToString("<br/>"))
        html.append(SECTION_END)
      }
      html.append(SECTIONS_END)
    }
    return html.toString()
  }

  private class Section(val header: String, val values: List<String>)

  private fun tagged(tag: String): List<Entry> = entries.filter { it.tag == tag }

  private fun addSection(project: Project, sections: MutableList<Section>, header: String, list: List<Entry>) {
    if (list.isNotEmpty()) sections.add(Section(header, list.map { markdown(project, it.text) }))
  }

  private fun paramHtml(project: Project, name: String, text: String): String {
    val description: String = markdown(project, text)
    val code = "<code>${escape(name)}</code>"
    return if (description.isBlank()) code else "$code &ndash; $description"
  }

  /** Inherited [other] fills in the tags these entries lack, like solc's `@inheritdoc` (params by name). */
  operator fun plus(other: NatSpecDoc) =
    NatSpecDoc(entries + other.entries.filter { e -> e.tag == "param" || entries.none { it.tag == e.tag } })

  /** The description of a single `@param`/`@return`, without the parameter name it starts with. */
  fun parameterHtml(project: Project, name: String?): String? {
    val entry = entries.firstOrNull { it.tag == "param" || it.tag == "return" } ?: return null
    val text = if (name != null && named(entry.text).first == name) named(entry.text).second else entry.text
    return CONTENT_START + markdown(project, text) + CONTENT_END
  }

  companion object {
    /** [comments] are the declaration's comment tokens (comments and NatSpec tags) in source order. */
    fun parse(comments: List<PsiElement>): NatSpecDoc {
      val entries = mutableListOf<Entry>()
      var tag = "notice"
      val text = StringBuilder()
      fun flush() {
        if (tag != "notice" || text.isNotBlank()) entries += Entry(tag, text.toString())
        text.setLength(0)
      }
      comments.forEachIndexed { i, e ->
        if (i > 0 && startsLine(comments[i - 1], e)) text.append('\n')
        if (e.elementType == SolidityTokenTypes.NAT_SPEC_TAG) {
          flush()
          tag = e.text.removePrefix("@")
        } else {
          text.append(clean(e.text))
        }
      }
      flush()
      return NatSpecDoc(entries)
    }

    /** Whether [next] is on a later line than [prev]: separate `///` lines are separate tokens. */
    private fun startsLine(prev: PsiElement, next: PsiElement): Boolean {
      val file = prev.containingFile
      if (file != next.containingFile) return true
      val from = prev.textRange.endOffset
      val to = next.textRange.startOffset
      if (from > to) return true
      return file.viewProvider.contents.subSequence(from, to).contains('\n')
    }

    /** Drops `/**`, `///`, `*/` and, on each following line, the indentation and leading `*`. */
    private fun clean(comment: String): String {
      val text = when {
        comment.startsWith("/**") -> comment.removePrefix("/**")
        comment.startsWith("///") -> comment.removePrefix("///")
        else -> comment
      }.removeSuffix("*/")
      return text.split('\n').mapIndexed { i, line ->
        if (i == 0) line else line.trimStart().let { if (it.startsWith("*")) it.substring(1) else it }
      }.joinToString("\n")
    }

    /**
     * The platform's Markdown rendering for doc comments; lines are trimmed first, since their indentation only
     * reflects the comment layout (and four spaces would make a code block).
     */
    private fun markdown(project: Project, text: String): String {
      val source = text.lines().joinToString("\n") { it.trim() }.trim()
      if (source.isEmpty()) return ""
      return DocMarkdownToHtmlConverter.convert(project, source).removeSurroundingParagraph()
    }

    /** A single paragraph goes inline (after a param name, in a section cell) without its `<p>`. */
    private fun String.removeSurroundingParagraph(): String =
      if (startsWith("<p>") && indexOf("<p>", 3) < 0) removePrefix("<p>").removeSuffix("</p>") else this

    /** `name rest` of a `@param` text. */
    private fun named(text: String): Pair<String, String> {
      val trimmed = text.trimStart()
      val name = trimmed.takeWhile { !it.isWhitespace() }
      return name to trimmed.substring(name.length)
    }

    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)

  }
}
