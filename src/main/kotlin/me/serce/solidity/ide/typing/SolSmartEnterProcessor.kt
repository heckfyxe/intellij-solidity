package me.serce.solidity.ide.typing

import com.intellij.lang.SmartEnterProcessorWithFixers
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.util.containers.OrderedSet
import me.serce.solidity.lang.core.SolidityLexer
import me.serce.solidity.lang.core.SolidityParserDefinition
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.core.YulFile
import me.serce.solidity.ide.inspections.allowsSolidityOlderThan

/**
 * Complete Current Statement (Ctrl/Cmd+Shift+Enter) for Solidity.
 *
 * Incomplete code produces a flat or misplaced PSI (a statement without `;` is a run of bare tokens inside its
 * block, a state variable without `;` even escapes its contract), so the fixers don't look at the PSI: they work on
 * the tokens of the statement at the caret, found by [SolStatementAnalyzer]. Every fixer changes the document at most
 * once per statement, the base class re-runs all fixers after each change, and [SolSmartEnterFinisher] then moves
 * the caret: into the (new) block or onto a new line after the statement.
 */
class SolSmartEnterProcessor : SmartEnterProcessorWithFixers() {
  init {
    addFixers(SolConditionParenFixer(), SolMissingParenFixer(), SolMissingBodyFixer(), SolMissingSemicolonFixer(), SolYulFixer())
    addEnterProcessors(SolSmartEnterFinisher())
  }

  // the fixers work on the caret line, not on PSI elements: the whole file is a stable anchor
  override fun getStatementAtCaret(editor: Editor, psiFile: PsiFile): PsiElement = psiFile

  override fun collectAllElements(element: PsiElement, result: OrderedSet<PsiElement>, recursive: Boolean) {}

  // there is no reformatter besides forge fmt, which must not run on a half-written statement
  override fun reformat(atCaret: PsiElement) {}

  override fun reformatBeforeEnter(atCaret: PsiElement): Boolean = false

  override fun restoreElementAtCaret(file: PsiFile, origElement: PsiElement, marker: RangeMarker): PsiElement = file

  // the base implementation reformats a PsiCodeBlock (Java), the finisher places the caret itself
  override fun moveCaretInsideBracesIfAny(editor: Editor, file: PsiFile) {}

  internal fun analyze(editor: Editor, file: PsiElement): SolStatement? {
    val document = editor.document
    val line = document.getLineNumber(editor.caretModel.offset)
    return analyzer(editor, file).analyze(line)
  }

  internal fun analyzer(editor: Editor, file: PsiElement) = createAnalyzer(editor.document, file)
}

internal fun createAnalyzer(document: Document, file: PsiElement): SolStatementAnalyzer {
  val psiFile = file.containingFile
  return SolStatementAnalyzer(
    document,
    yulFile = psiFile is YulFile,
    legacyFallback = (psiFile as? SolidityFile)?.allowsSolidityOlderThan(6) == true,
  )
}

/** `if x` and `while x` get the parens of their condition: `if (x`, closed by [SolMissingParenFixer]. */
private class SolConditionParenFixer : SmartEnterProcessorWithFixers.Fixer<SolSmartEnterProcessor>() {
  override fun apply(editor: Editor, processor: SolSmartEnterProcessor, element: PsiElement) {
    val st = processor.analyze(editor, element) ?: return
    if (st.inYul || st.isEmpty || st.kind != Kind.CONDITION || st.last == st.start) return
    val head = st.tokens[st.start]
    if (head.text != "if" && head.text != "while") return
    val first = st.tokens[st.start + 1]
    if (first.text == "(") return
    editor.document.insertString(first.start, "(")
  }
}

/** Inside inline assembly and Yul files: closes calls and blocks and adds the block of `if`, `for`, `switch` cases and functions. */
private class SolYulFixer : SmartEnterProcessorWithFixers.Fixer<SolSmartEnterProcessor>() {
  override fun apply(editor: Editor, processor: SolSmartEnterProcessor, element: PsiElement) {
    val analyzer = processor.analyzer(editor, element)
    val line = editor.document.getLineNumber(editor.caretModel.offset)
    val (offset, text) = analyzer.yulFix(line) ?: return
    editor.document.insertString(offset, text)
  }
}

private class SolMissingParenFixer : SmartEnterProcessorWithFixers.Fixer<SolSmartEnterProcessor>() {
  override fun apply(editor: Editor, processor: SolSmartEnterProcessor, element: PsiElement) {
    val st = processor.analyze(editor, element) ?: return
    if (st.inYul || st.unclosed.isEmpty() || st.isEmpty) return
    val closers = st.unclosed.reversed().joinToString("") { closerOf(it.text) }
    val insertAt = if (st.endsWithHeaderBrace) st.tokens[st.last - 1].end else st.lastToken.end
    editor.document.insertString(insertAt, closers)
  }
}

private class SolMissingBodyFixer : SmartEnterProcessorWithFixers.Fixer<SolSmartEnterProcessor>() {
  override fun apply(editor: Editor, processor: SolSmartEnterProcessor, element: PsiElement) {
    val st = processor.analyze(editor, element) ?: return
    if (st.inYul || st.unclosed.isNotEmpty() || !st.needsBody()) return
    val text = if (st.kind == Kind.CALLABLE && (st.inInterface || st.isAbstractVirtualFunction())) ";" else " {}"
    val anchor = if (st.isEmpty) st.tokens[st.resetToken].end else st.lastToken.end
    editor.document.insertString(anchor, text)
  }
}

private class SolMissingSemicolonFixer : SmartEnterProcessorWithFixers.Fixer<SolSmartEnterProcessor>() {
  override fun apply(editor: Editor, processor: SolSmartEnterProcessor, element: PsiElement) {
    val st = processor.analyze(editor, element) ?: return
    if (st.inYul || st.inEnum || st.isEmpty || st.unclosed.isNotEmpty() || st.kind != Kind.SIMPLE) return
    if (st.lastToken.text in setOf(";", "{", "}", ",")) return
    editor.document.insertString(st.lastToken.end, ";")
  }
}

/** Puts the caret into the block that ends the statement, or onto a new line after the statement. */
private class SolSmartEnterFinisher : SmartEnterProcessorWithFixers.FixEnterProcessor() {
  override fun doEnter(atCaret: PsiElement, file: PsiFile, editor: Editor, modified: Boolean): Boolean {
    val document = editor.document
    val analyzer = createAnalyzer(document, file)
    val tokens = analyzer.tokens
    var st = analyzer.analyze(document.getLineNumber(editor.caretModel.offset))
    if (st == null) {
      enter(editor)
      return true
    }
    // the body of `if (...)` etc. on the next lines: `{` on its own line, or a braceless statement
    var steps = 0
    while (st!!.bodyFollows && steps++ < MAX_BODY_STEPS) {
      val next = st.last + 1
      if (tokens[next].text == "{") {
        enterAfterBrace(editor, analyzer, next)
        return true
      }
      st = analyzer.analyze(document.getLineNumber(tokens[next].start)) ?: break
    }
    val lastIdx = st.last
    if (tokens[lastIdx].text == "{" && !analyzer.isNestingBrace(lastIdx)) {
      enterAfterBrace(editor, analyzer, lastIdx)
    } else {
      editor.caretModel.moveToOffset(document.getLineEndOffset(document.getLineNumber(tokens[lastIdx].start)))
      enter(editor)
    }
    return true
  }

  private fun enterAfterBrace(editor: Editor, analyzer: SolStatementAnalyzer, open: Int) {
    val document = editor.document
    val tokens = analyzer.tokens
    val brace = tokens[open]
    val close = analyzer.braceMatch[open]
    if (close == open + 1 && document.charsSequence.subSequence(brace.end, tokens[close].start).isBlank()) {
      // an empty block: join the braces so that Enter between them lays the block out
      document.deleteString(brace.end, tokens[close].start)
    }
    editor.caretModel.moveToOffset(brace.end)
    enter(editor)
  }

  private companion object {
    const val MAX_BODY_STEPS = 10
  }
}

/** A real Enter: [plainEnter] of the base class starts a new line after the caret line instead. */
private fun enter(editor: Editor) {
  EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
    .execute(editor, editor.caretModel.currentCaret, EditorUtil.getEditorDataContext(editor))
}

private fun closerOf(open: String) = when (open) {
  "(" -> ")"
  "[" -> "]"
  else -> "}"
}

internal class SolToken(val type: IElementType, val text: String, val start: Int, val end: Int)

internal enum class Kind {
  /** `if`, `while`, `for`: a parenthesized condition followed by a body. */
  CONDITION,

  /** `else`, `do`, `try`, `catch`, `unchecked`, `assembly`: a block follows. */
  BLOCK_HEADER,

  /** functions, constructors, modifiers, fallback and receive: a block, or `;` in an interface. */
  CALLABLE,

  /** contracts, interfaces, libraries, structs and enums. */
  TYPE_HEADER,

  /** everything ending with `;`. */
  SIMPLE,
}

internal class SolStatement(
  val tokens: List<SolToken>,
  /** index of the first token of the statement; `> last` when the statement is empty (e.g. right after `else`). */
  val start: Int,
  val last: Int,
  /** the token that started the statement (`else`, `do`, or the `)` of a braceless `if (...)`), or -1. */
  val resetToken: Int,
  val kind: Kind,
  /** open `(`, `[` and nesting `{` tokens not closed by the end of the statement. */
  val unclosed: List<SolToken>,
  /** a block `{` that ends the statement while parens are still open (`if (x {`). */
  val endsWithHeaderBrace: Boolean,
  /** a body (a `{` or a braceless statement) follows on the next lines. */
  val bodyFollows: Boolean,
  val inInterface: Boolean,
  val inEnum: Boolean,
  val inYul: Boolean,
  /** directly inside an `abstract contract`. */
  val inAbstract: Boolean = false,
) {
  val isEmpty get() = start > last
  val lastToken get() = tokens[last]

  /** A `virtual` function in an abstract contract: unimplemented, it ends with `;`. */
  fun isAbstractVirtualFunction(): Boolean =
    inAbstract && !isEmpty && tokens[start].text == "function" && (start..last).any { tokens[it].text == "virtual" }

  fun needsBody(): Boolean {
    if (bodyFollows) return false
    if (isEmpty) return resetToken >= 0 && tokens[resetToken].text in setOf("else", "do")
    if (lastToken.text == "{" || lastToken.text == ";" || lastToken.text == "}") return false
    return kind == Kind.CONDITION || kind == Kind.BLOCK_HEADER || kind == Kind.CALLABLE || kind == Kind.TYPE_HEADER
  }
}

/**
 * Finds the bounds and the shape of the statement on a line from the tokens alone, so that it works on code that
 * doesn't parse.
 */
internal class SolStatementAnalyzer(
  private val document: Document,
  /** a standalone `.yul` file: everything is Yul. */
  private val yulFile: Boolean = false,
  /** the pragma allows Solidity < 0.6, where `function()` is the fallback function. */
  private val legacyFallback: Boolean = false,
) {
  val tokens: List<SolToken> = lex(document.charsSequence)
  val braceMatch: IntArray = matchBraces()

  fun analyze(line: Int): SolStatement? {
    var current = line
    repeat(MAX_LINES) {
      val st = analyzeLine(current) ?: return null
      val next = continuationLine(st) ?: return st
      current = next
    }
    return null
  }

  /** The line the statement continues on, when it doesn't end on the analyzed line. */
  private fun continuationLine(st: SolStatement): Int? {
    if (st.isEmpty || st.endsWithHeaderBrace) return null
    val lastText = st.lastToken.text
    if (lastText == ";" || lastText == "{" || lastText == "}") return null
    if (st.unclosed.isNotEmpty()) return closingLine(st)
    val next = st.last + 1
    if (next >= tokens.size) return null
    val nextToken = tokens[next]
    if (nextToken.text == "{" || nextToken.text == "}") return null
    if (lineOf(nextToken.start) == lineOf(st.lastToken.start)) return null
    // a deeper indented line continues the statement, except for the braceless body of `if (...)`
    if (st.kind == Kind.CONDITION || st.kind == Kind.BLOCK_HEADER) return null
    val headerIndent = indentOf(tokens[st.start].start)
    return if (indentOf(nextToken.start) > headerIndent) lineOf(nextToken.start) else null
  }

  /** The line where the parens still open at the end of the statement get closed, or null if they never are. */
  private fun closingLine(st: SolStatement): Int? {
    val stack = ArrayList<String>()
    for (t in st.unclosed) stack.add(t.text)
    var forParen = isForStatement(st)
    for (i in st.last + 1 until tokens.size) {
      val t = tokens[i]
      when (t.text) {
        "(", "[" -> stack.add(t.text)
        ")", "]" -> {
          stack.removeLastOrNull()
          if (stack.isEmpty()) return lineOf(t.start)
        }
        "{" -> if (isNestingBrace(i)) stack.add("{") else return null
        "}" -> {
          if (stack.lastOrNull() != "{") return null
          stack.removeLast()
          if (stack.isEmpty()) return lineOf(t.start)
        }
        ";" -> if (!forParen) return null
      }
      if (stack.isEmpty()) forParen = false
    }
    return null
  }

  private fun isForStatement(st: SolStatement) = !st.isEmpty && tokens[st.start].text == "for"

  private fun analyzeLine(line: Int): SolStatement? {
    if (line < 0 || line >= document.lineCount) return null
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    var last = tokens.indexOfLast { it.start in lineStart..lineEnd }.takeIf { it >= 0 } ?: return null
    // `header {}`: the statement ends with the `{` of its empty block
    if (tokens[last].text == "}" && braceMatch[last] == last - 1 && tokens[last - 1].start >= lineStart &&
      !isNestingBrace(last)
    ) {
      last--
    }
    // a closing `}`: nothing to complete
    if (tokens[last].text == "}" && !isNestingBrace(last)) {
      return SolStatement(tokens, last + 1, last, -1, Kind.SIMPLE, emptyList(), false, false, false, false, isYul(last))
    }

    val boundary = findBoundary(last)
    var start = boundary + 1
    var resetToken = -1
    val stack = ArrayList<Int>()
    var conditionParen = -1
    var headerBrace = false
    var i = start
    while (i <= last) {
      val t = tokens[i]
      if (stack.isEmpty()) {
        when {
          t.text == ";" -> { start = i + 1; resetToken = -1 }
          (t.text == "{" || t.text == "}") && !isNestingBrace(i) -> { start = i + 1; resetToken = -1 }
          (t.text == "else" || t.text == "do") -> { start = i + 1; resetToken = i }
          t.text == "(" && i == start + 1 && tokens[start].text in CONDITION_KEYWORDS -> {
            conditionParen = i
            stack.add(i)
          }
          isOpen(i) -> stack.add(i)
        }
      } else {
        when {
          t.text == "{" && !isNestingBrace(i) && i == last -> { headerBrace = true }
          isOpen(i) -> stack.add(i)
          t.text == ")" || t.text == "]" || t.text == "}" -> {
            val open = stack.removeLast()
            // the condition of `if (...)` is closed and a braceless body follows on the same line
            if (stack.isEmpty() && open == conditionParen && i < last) {
              start = i + 1
              resetToken = i
              conditionParen = -1
            }
          }
        }
      }
      i++
    }

    val unclosed = stack.map { tokens[it] }
    val enclosingOpen = enclosingBrace(minOf(start, last + 1) - 1)
    val enclosing = if (enclosingOpen >= 0) headerKeyword(enclosingOpen) else null
    val kind = if (start > last) Kind.SIMPLE else kindOf(start, last, enclosing)
    val inInterface = enclosing == "interface"
    val inEnum = enclosing == "enum"
    val inAbstract = enclosingOpen >= 0 && isAbstractHeader(enclosingOpen)
    val partial = SolStatement(
      tokens, start, last, resetToken, kind, unclosed, headerBrace,
      bodyFollows = false, inInterface = inInterface, inEnum = inEnum, inYul = isYul(last), inAbstract = inAbstract
    )
    return SolStatement(
      tokens, start, last, resetToken, kind, unclosed, headerBrace,
      bodyFollows = bodyFollows(partial), inInterface = inInterface, inEnum = inEnum, inYul = partial.inYul,
      inAbstract = inAbstract
    )
  }

  private fun bodyFollows(st: SolStatement): Boolean {
    val next = st.last + 1
    if (next >= tokens.size) return false
    val nextToken = tokens[next]
    if (nextToken.text == "{" && !isNestingBrace(next)) return true
    if (nextToken.text == "}") return false
    if (lineOf(nextToken.start) == lineOf(st.lastToken.start)) return false
    // a braceless body on the next, deeper indented line
    if (st.kind == Kind.CONDITION || (st.isEmpty && st.resetToken >= 0) || st.kind == Kind.BLOCK_HEADER) {
      val headerStart = if (st.isEmpty) st.resetToken else st.start
      return indentOf(nextToken.start) > indentOf(tokens[headerStart].start)
    }
    return false
  }

  private fun kindOf(start: Int, last: Int, enclosing: String?): Kind {
    val first = tokens[start].text
    val second = tokens.getOrNull(start + 1)?.text
    return when (first) {
      "if", "for" -> Kind.CONDITION
      "while" -> if (isDoWhile(start)) Kind.SIMPLE else Kind.CONDITION
      "try", "catch", "unchecked", "assembly" -> Kind.BLOCK_HEADER
      // `function (uint) external f;` is a variable of a function type, `function() payable` a pre-0.6 fallback
      "function" -> if (second != "(") Kind.CALLABLE else if (isLegacyFallback(start, last, enclosing)) Kind.CALLABLE else Kind.SIMPLE
      "constructor", "modifier", "fallback", "receive" -> Kind.CALLABLE
      "contract", "interface", "library", "abstract", "struct", "enum" -> Kind.TYPE_HEADER
      else -> Kind.SIMPLE
    }
  }

  /**
   * `function() external payable` directly in a contract, when the pragma allows Solidity < 0.6. A variable of a
   * function type always ends with its name (or has an initializer), which a fallback header never does.
   */
  private fun isLegacyFallback(start: Int, last: Int, enclosing: String?): Boolean {
    if (!legacyFallback || enclosing !in setOf("contract", "library", "interface")) return false
    if ((start..last).any { tokens[it].text == "=" }) return false
    val end = tokens[last]
    return end.type != SolidityTokenTypes.IDENTIFIER || end.text in FUNCTION_HEADER_WORDS
  }

  private fun isAbstractHeader(open: Int): Boolean {
    val boundary = findBoundaryBefore(open - 1)
    return tokens.getOrNull(boundary + 1)?.text == "abstract"
  }

  /** `do { ... } while (x)` */
  private fun isDoWhile(whileIdx: Int): Boolean {
    val prev = whileIdx - 1
    if (prev < 0 || tokens[prev].text != "}") return false
    val open = braceMatch[prev]
    return open > 0 && tokens[open - 1].text == "do"
  }

  /** Walks back from [from] to the `{` or `}` that precedes the statement, skipping parenthesized groups. */
  private fun findBoundary(from: Int): Int {
    var depth = 0
    var i = from
    while (i >= 0) {
      val t = tokens[i]
      when (t.text) {
        ")", "]" -> depth++
        "(", "[" -> depth--
        "}" -> if (isNestingBrace(i)) depth++ else if (depth <= 0 && i != from) return i else {
          val m = braceMatch[i]
          if (m >= 0) i = m
        }
        "{" -> if (isNestingBrace(i)) depth-- else if (depth <= 0 && i != from) return i
      }
      i--
    }
    return -1
  }

  /** The first keyword of the declaration whose block contains the token at [before], e.g. `interface`. */
  private fun enclosingHeader(before: Int): String? {
    val open = enclosingBrace(before)
    if (open < 0) return null
    return headerKeyword(open)
  }

  private fun enclosingBrace(before: Int): Int {
    var i = before
    while (i >= 0) {
      val t = tokens[i]
      if (t.text == "}") {
        val m = braceMatch[i]
        i = if (m >= 0) m - 1 else i - 1
        continue
      }
      if (t.text == "{" && !isNestingBrace(i)) return i
      i--
    }
    return -1
  }

  private fun headerKeyword(open: Int): String? {
    val boundary = findBoundaryBefore(open - 1)
    var first = boundary + 1
    while (first < open && (tokens[first].text == "else" || tokens[first].text == "do")) first++
    if (first >= open) return null
    val text = tokens[first].text
    return if (text == "abstract") tokens.getOrNull(first + 1)?.text else text
  }

  private fun findBoundaryBefore(from: Int): Int {
    var depth = 0
    var i = from
    while (i >= 0) {
      when (tokens[i].text) {
        ")", "]" -> depth++
        "(", "[" -> depth--
        ";" -> if (depth <= 0) return i
        "{" -> if (depth <= 0 && !isNestingBrace(i)) return i
        "}" -> if (depth <= 0 && !isNestingBrace(i)) return i
      }
      i--
    }
    return -1
  }

  private fun isYul(idx: Int): Boolean = yulFile || inYul(idx)

  /**
   * The one insertion that completes the Yul statement on [line]: the `)` and `}` it leaves open, otherwise the
   * missing block of `if cond`, `for {} cond {}`, `switch x case 0`, `default` and `function f(a) -> r`.
   */
  fun yulFix(line: Int): Pair<Int, String>? {
    if (line < 0 || line >= document.lineCount) return null
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val last = tokens.indexOfLast { it.start in lineStart..lineEnd }.takeIf { it >= 0 } ?: return null
    if (!isYul(last)) return null
    var start = yulStatementStart(tokens.indexOfFirst { it.start >= lineStart })
    if (tokens[start].text == "assembly") return null

    // open parens and blocks, from the first token of the statement
    val stack = ArrayList<Int>()
    for (i in start..last) {
      when (tokens[i].text) {
        "(", "{" -> stack.add(i)
        ")" -> if (stack.lastOrNull()?.let { tokens[it].text } == "(") stack.removeLast()
        "}" -> {
          while (stack.isNotEmpty() && tokens[stack.last()].text == "(") stack.removeLast()
          if (stack.isEmpty()) start = i + 1 else stack.removeLast()
        }
      }
    }
    if (start > last) return null
    val closers = StringBuilder()
    for (open in stack.asReversed()) {
      if (tokens[open].text == "(") {
        closers.append(")")
      } else if (open != last && isUnclosedYulBlock(open, start)) {
        closers.append(" }")
      } else {
        break
      }
    }
    if (closers.isNotEmpty()) return tokens[last].end to closers.toString()
    if (stack.isNotEmpty()) return null

    // the block of a control statement or function
    val next = tokens.getOrNull(last + 1)
    if (next?.text == "{" && lineOf(next.start) != line) return null
    val shape = StringBuilder()
    var i = start + 1
    while (i <= last) {
      if (tokens[i].text == "{") {
        shape.append('B')
        i = braceMatch[i].takeIf { it in i..last } ?: return null
      } else if (shape.lastOrNull() != 'E') {
        shape.append('E')
      }
      i++
    }
    val lastText = tokens[last].text
    val body = when (tokens[start].text) {
      "if", "case" -> shape.toString() == "E"
      "function" -> 'B' !in shape && tokens.getOrNull(start + 1)?.type == SolidityTokenTypes.IDENTIFIER
      "default" -> shape.isEmpty()
      "switch" -> lastText == "default" || (last - 1 > start && tokens[last - 1].text == "case")
      "for" -> return when (shape.toString()) {
        "BEB" -> tokens[last].end to " {}"
        "BE" -> tokens[last].end to " {} {}"
        else -> null
      }
      else -> false
    }
    return if (body) tokens[last].end to " {}" else null
  }

  /** The first token of the Yul statement that [first], the first token of a line, belongs to. */
  private fun yulStatementStart(first: Int): Int {
    var start = first
    while (true) {
      var depth = 0
      var i = start - 1
      var opener = -1
      while (i >= 0) {
        val text = tokens[i].text
        if (text == ")") depth++
        else if (text == "(") {
          if (depth == 0) {
            opener = i
            break
          }
          depth--
        } else if ((text == "{" || text == "}") && depth == 0) break
        i--
      }
      if (opener < 0) return start
      val openerLine = document.getLineStartOffset(lineOf(tokens[opener].start))
      start = tokens.indexOfFirst { it.start >= openerLine }
    }
  }

  /** A `{` followed by more tokens on its line whose block isn't closed by a `}` at the statement's indent or deeper. */
  private fun isUnclosedYulBlock(open: Int, start: Int): Boolean {
    val close = braceMatch[open]
    return close < 0 || indentOf(tokens[close].start) < indentOf(tokens[start].start)
  }

  private fun inYul(idx: Int): Boolean {
    var open = enclosingBrace(idx)
    while (open >= 0) {
      if (headerKeyword(open) == "assembly") return true
      open = enclosingBrace(open - 1)
    }
    return false
  }

  private fun isOpen(i: Int): Boolean {
    val text = tokens[i].text
    return text == "(" || text == "[" || (text == "{" && isNestingBrace(i))
  }

  /**
   * A `{` (or the `}` closing it) that is part of an expression or a declaration rather than a block: struct
   * literals and named arguments (`f({a: 1})`), call options (`to.call{value: v}("")`), `import {A} from` and
   * `using {f} for T`.
   */
  fun isNestingBrace(i: Int): Boolean {
    val open = when (tokens[i].text) {
      "{" -> i
      "}" -> braceMatch[i].takeIf { it >= 0 } ?: return false
      else -> return false
    }
    val prev = tokens.getOrNull(open - 1) ?: return false
    if (prev.text in NESTING_BRACE_PREDECESSORS) return true
    if (prev.type == SolidityTokenTypes.IDENTIFIER || prev.text == ")" || prev.text == "]") {
      val next = tokens.getOrNull(open + 1)
      val afterNext = tokens.getOrNull(open + 2)
      return next?.type == SolidityTokenTypes.IDENTIFIER && afterNext?.text == ":"
    }
    return false
  }

  private fun matchBraces(): IntArray {
    val result = IntArray(tokens.size) { -1 }
    val stack = ArrayList<Int>()
    for ((i, t) in tokens.withIndex()) {
      when (t.text) {
        "{" -> stack.add(i)
        "}" -> {
          val open = stack.removeLastOrNull() ?: continue
          result[i] = open
          result[open] = i
        }
      }
    }
    return result
  }

  private fun lineOf(offset: Int) = document.getLineNumber(offset)

  private fun indentOf(offset: Int): Int {
    val chars = document.charsSequence
    var i = document.getLineStartOffset(lineOf(offset))
    var width = 0
    while (i < chars.length && (chars[i] == ' ' || chars[i] == '\t')) {
      width += if (chars[i] == '\t') 4 else 1
      i++
    }
    return width
  }

  companion object {
    private const val MAX_LINES = 50
    private val CONDITION_KEYWORDS = setOf("if", "while", "for")
    private val FUNCTION_HEADER_WORDS = setOf(
      "payable", "external", "public", "internal", "private", "pure", "view", "constant", "virtual", "override"
    )
    private val NESTING_BRACE_PREDECESSORS = setOf("(", ",", "=", "[", ":", "?", "return", "using", "import")

    private fun lex(text: CharSequence): List<SolToken> {
      val lexer = SolidityLexer()
      lexer.start(text)
      val result = ArrayList<SolToken>()
      while (true) {
        val type = lexer.tokenType ?: break
        if (!SolidityParserDefinition.WHITE_SPACES.contains(type) && !SolidityParserDefinition.COMMENTS.contains(type)) {
          result.add(SolToken(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd))
        }
        lexer.advance()
      }
      return result
    }
  }
}
