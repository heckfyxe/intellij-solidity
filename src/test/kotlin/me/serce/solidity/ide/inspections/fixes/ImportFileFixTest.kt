package me.serce.solidity.ide.inspections.fixes

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolReferenceElement
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.utils.SolTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ImportFileFixTest : SolTestBase() {
  fun testInvokeAddsImport() {
    myFixture.addFileToProject("B.sol", "contract B {}")
    val main = myFixture.addFileToProject("Main.sol", "contract Main is B {}")
    val ref = PsiTreeUtil.findChildrenOfType(main, SolReferenceElement::class.java).first { it.text == "B" }
    ImportFileFix(ref).invoke(project, main, ref, ref)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val expected = "import {B} from \"./B.sol\";\n\ncontract Main is B {}"
    assertEquals(expected, main.text.trim())
  }

  fun testInterfaceGetsNamedImport() {
    myFixture.addFileToProject("IToken.sol", "interface IToken { function f() external; }")
    val main = myFixture.addFileToProject("Main.sol", "contract Main { IToken t; }")
    val ref = PsiTreeUtil.findChildrenOfType(main, SolReferenceElement::class.java).first { it.text == "IToken" }
    ImportFileFix(ref).invoke(project, main, ref, ref)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals("import {IToken} from \"./IToken.sol\";\n\ncontract Main { IToken t; }", main.text.trim())
  }

  fun testInvokeWithEditorOnAmbiguousReferenceExecutesAmbiguousImportPath() {
    myFixture.addFileToProject("MathA.sol", "library Math { function max(uint a, uint b) internal pure returns (uint) { return a; } }")
    myFixture.addFileToProject("MathB.sol", "library Math { function min(uint a, uint b) internal pure returns (uint) { return b; } }")
    val mainText = """
      contract Main {
        function f(uint a, uint b) public pure returns (uint) {
          return Math.max(a, b);
        }
      }
    """.trimIndent()
    val main = myFixture.configureByText("Main.sol", mainText)
    val ref = PsiTreeUtil.findChildrenOfType(main, SolReferenceElement::class.java).first { it.text == "Math" }
    val suggestions = SolResolver.resolveTypeName(ref).map { it.containingFile }.toSet()
    assertEquals(2, suggestions.size)

    ImportFileFix(ref).invoke(project, myFixture.editor, main)
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val text = main.text.trim()
    assertTrue(text.contains("import {Math} from \"./MathA.sol\";") || text.contains("import {Math} from \"./MathB.sol\";"))
  }
}
