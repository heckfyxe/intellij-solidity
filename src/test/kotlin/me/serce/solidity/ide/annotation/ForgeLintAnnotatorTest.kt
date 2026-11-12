package me.serce.solidity.ide.annotation

import com.intellij.lang.annotation.HighlightSeverity
import me.serce.solidity.utils.SolLightPlatformCodeInsightFixtureTestCase

class ForgeLintAnnotatorTest : SolLightPlatformCodeInsightFixtureTestCase() {

  private val text = """
    contract C {
        // é
        uint256 internal x;
    }
  """.trimIndent()

  // byte offsets: `é` is two bytes in UTF-8, one char
  private val start = text.substring(0, text.indexOf("uint256")).toByteArray().size
  private val end = start + "uint256 internal x;".length

  private fun diagnostic(file: String, code: String?, level: String = "warning") =
    """{"${'$'}message_type":"diagnostic","message":"state variable is read but never written","code":""" +
      (code?.let { """{"code":"$it","explanation":null}""" } ?: "null") +
      ""","level":"$level","spans":[{"file_name":"$file","byte_start":$start,"byte_end":$end,"line_start":3,""" +
      """"is_primary":true,"suggested_replacement":null}],"children":[{"message":"https://getfoundry.sh/forge/linting/$code",""" +
      """"level":"help","spans":[],"children":[]}],"rendered":"warning..."}"""

  fun testParsesLintDiagnostics() {
    val output = listOf(
      "Compiling...",
      diagnostic("src/Lint.sol", "uninitialized-state"),
      diagnostic("src/Lint.sol", "mixed-case-variable", level = "note"),
      diagnostic("src/Other.sol", "uninitialized-state"),
      diagnostic("src/Lint.sol", null, level = "error"),
    ).joinToString("\n")

    val issues = ForgeLintAnnotator.parse(output, "src/Lint.sol", text)

    assertEquals(2, issues.size)
    val first = issues[0]
    assertEquals("uninitialized-state", first.code)
    assertEquals(HighlightSeverity.WARNING, first.severity)
    assertEquals("uint256 internal x;", text.substring(first.start, first.end))
    assertEquals("https://getfoundry.sh/forge/linting/uninitialized-state", first.helpUrl)
    assertEquals(HighlightSeverity.WEAK_WARNING, issues[1].severity)
  }

  fun testIgnoresGarbage() {
    assertEmpty(ForgeLintAnnotator.parse("{not json\nerror: oops", "src/Lint.sol", text))
  }

  fun testSuppressFixAddsCommentAboveWithIndent() {
    myFixture.configureByText("Lint.sol", """
      contract C {
          uint256 internal <caret>x;
      }
    """.trimIndent())
    SuppressForgeLintFix("uninitialized-state").let { fix ->
      com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
        fix.invoke(project, myFixture.editor, myFixture.file)
      }
    }
    myFixture.checkResult("""
      contract C {
          // forge-lint: disable-next-line(uninitialized-state)
          uint256 internal x;
      }
    """.trimIndent())
  }
}
