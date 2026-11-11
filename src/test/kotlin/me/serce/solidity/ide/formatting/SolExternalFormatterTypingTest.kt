package me.serce.solidity.ide.formatting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import me.serce.solidity.settings.FormatterType
import me.serce.solidity.settings.SoliditySettings
import me.serce.solidity.utils.SolTestBase

/** With Foundry (or no formatter) selected, typing still indents, but the IDE never reformats the code. */
class SolExternalFormatterTypingTest : SolTestBase() {
  private lateinit var originalFormatterType: FormatterType

  override fun setUp() {
    super.setUp()
    originalFormatterType = SoliditySettings.getInstance(project).formatterType
  }

  override fun tearDown() = try {
    SoliditySettings.getInstance(project).formatterType = originalFormatterType
  } finally {
    super.tearDown()
  }

  fun testEnterIndentsWithFoundry() = checkEnterIndents(FormatterType.FOUNDRY)
  fun testEnterIndentsWithoutFormatter() = checkEnterIndents(FormatterType.DISABLED)

  fun testClosingBraceIsOutdentedWithFoundry() {
    SoliditySettings.getInstance(project).formatterType = FormatterType.FOUNDRY
    checkByText(
      "contract A {\n    function f() public {\n        uint a = 1;\n        <caret>\n}",
      "contract A {\n    function f() public {\n        uint a = 1;\n    }<caret>\n}"
    ) { myFixture.type('}') }
  }

  fun testReformatLeavesCodeAloneWithFoundry() = checkReformatLeavesCodeAlone(FormatterType.FOUNDRY)
  fun testReformatLeavesCodeAloneWithoutFormatter() = checkReformatLeavesCodeAlone(FormatterType.DISABLED)

  private fun checkEnterIndents(type: FormatterType) {
    SoliditySettings.getInstance(project).formatterType = type
    checkByText(
      "contract A {\n    function f() public {<caret>}\n}",
      "contract A {\n    function f() public {\n        <caret>\n    }\n}"
    ) { myFixture.type('\n') }
  }

  private fun checkReformatLeavesCodeAlone(type: FormatterType) {
    SoliditySettings.getInstance(project).formatterType = type
    val code = "contract A{\nfunction f() public {uint a=1;}\n}"
    myFixture.configureByText("A.sol", code)
    WriteCommandAction.runWriteCommandAction(project) {
      CodeStyleManager.getInstance(project).reformatText(myFixture.file, 0, code.length - 2)
    }
    assertEquals(code, myFixture.editor.document.text)
  }
}
