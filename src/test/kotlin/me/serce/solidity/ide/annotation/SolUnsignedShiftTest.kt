package me.serce.solidity.ide.annotation

import com.intellij.lang.annotation.HighlightSeverity
import me.serce.solidity.utils.SolTestBase

class SolUnsignedShiftTest : SolTestBase() {
  fun testUnsignedShiftParsesAndIsReported() {
    myFixture.configureByText("A.sol", """
      contract A {
          function f(uint a) public pure returns (uint) {
              a >>>= 1;
              return a >>> 2;
          }
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
    assertEquals(
      listOf(">>>=" to "Solidity doesn't support '>>>=', use '>>='", ">>>" to "Solidity doesn't support '>>>', use '>>'"),
      errors.map { it.text to it.description }
    )
  }
}
