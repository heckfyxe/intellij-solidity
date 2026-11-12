package me.serce.solidity.lang.completion

import com.intellij.psi.PsiFile
import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

abstract class SolCompletionTestBase : SolTestBase() {
  protected fun checkCompletion(expected: Set<String>, @Language("Solidity") code: String, strict: Boolean = false) {
    // keywords written with a trailing space below are looked up without it; the space is added on insert
    val required = expected.mapTo(hashSetOf()) { it.trimEnd() }
    InlineFile(code).withCaret()
    val variants = myFixture.completeBasic()
    checkNotNull(variants) {
      "Expected completions that contain $required, but no completions found"
    }
    val completions = variants.map { it.lookupString }.toHashSet()
    if (strict) {
      assertEquals(required.toHashSet(), completions)
    } else {
      assertTrue("$completions doesn't contain all $required", completions.containsAll(required))
    }
  }

  protected fun checkResultAfterCompletion(
    @Language("Solidity") before: String, @Language("Solidity") expected: String
  ) {
    InlineFile(before).withCaret()
    myFixture.completeBasic()
    if (myFixture.lookupElements != null && myFixture.lookupElements!!.size == 1) {
      myFixture.type('\n')
    }
    myFixture.checkResult(expected)
  }

  protected fun checkResultAfterCompletion(expected: PsiFile) {
    myFixture.completeBasic()
    if (myFixture.lookupElements != null && myFixture.lookupElements!!.size == 1) {
      myFixture.type('\n')
    }
    myFixture.checkResult(expected.text)
  }

  val elementaryType = hashSetOf(
    "address ",
    "string ",
    "fixed",
    "ufixed",
    "uint8 ",
    "uint16 ",
    "uint32 ",
    "uint64 ",
    "uint128 ",
    "uint256 ",
    "int8 ",
    "int16 ",
    "int32 ",
    "int64 ",
    "int128 ",
    "int256 ",
    "bytes ",
    "bytes4 ",
    "bytes8 ",
    "bytes16 ",
    "bytes20 ",
    "bytes32 ",
    "bool "
  )

  val dataLocation = hashSetOf(
    "memory",
    "storage",
    "calldata"
  )

  val stateMutability = hashSetOf(
    "pure ", "view ", "payable "
  )

  val contractBodyElement = hashSetOf(
    "constructor",
    "function ",
    "modifier ",
    "fallback",
    "receive",
    "struct ",
    "enum ",
    "type ",
    "event ",
    "error ",
    "using ",
    "mapping",
    "this"
  ) + elementaryType
}
