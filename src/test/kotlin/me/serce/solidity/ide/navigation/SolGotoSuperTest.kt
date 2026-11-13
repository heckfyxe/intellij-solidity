package me.serce.solidity.ide.navigation

import com.intellij.psi.PsiNamedElement
import me.serce.solidity.utils.SolTestBase

class SolGotoSuperTest : SolTestBase() {
  private fun targetsAtCaret(code: String): List<String> {
    InlineFile(code)
    val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
    return SolGotoSuperHandler.superTargets(element).map { target ->
      val contract = com.intellij.psi.util.PsiTreeUtil.getParentOfType(target, me.serce.solidity.lang.psi.SolContractDefinition::class.java)
      listOfNotNull(contract?.name, (target as PsiNamedElement).name).joinToString(".")
    }
  }

  fun testFunction() = assertEquals(
    listOf("B.f", "A.f"),
    targetsAtCaret("""
      contract A { function f() public virtual {} }
      contract B is A { function f() public virtual override {} }
      contract C is B { function /*caret*/f() public override {} }
    """.trimIndent())
  )

  fun testModifier() = assertEquals(
    listOf("A.m"),
    targetsAtCaret("""
      contract A { modifier m() virtual { _; } }
      contract B is A { modifier /*caret*/m() override { _; } }
    """.trimIndent())
  )

  fun testPublicStateVariable() = assertEquals(
    listOf("I.total"),
    targetsAtCaret("""
      interface I { function total() external view returns (uint); }
      contract B is I { uint public override /*caret*/total; }
    """.trimIndent())
  )

  fun testContract() = assertEquals(
    listOf("A", "I"),
    targetsAtCaret("""
      interface I {}
      contract A {}
      contract /*caret*/B is A, I {}
    """.trimIndent())
  )

  fun testInsideBodyUsesEnclosingFunction() = assertEquals(
    listOf("A.f"),
    targetsAtCaret("""
      contract A { function f() public virtual {} }
      contract B is A { function f() public override { uint /*caret*/x = 1; } }
    """.trimIndent())
  )
}
