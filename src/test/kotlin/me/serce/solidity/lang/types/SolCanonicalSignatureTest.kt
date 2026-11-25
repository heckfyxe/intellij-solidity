package me.serce.solidity.lang.types

import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.utils.SolTestBase

class SolCanonicalSignatureTest : SolTestBase() {
  private fun signatures(code: String): List<String?> {
    InlineFile(code)
    return PsiTreeUtil.findChildrenOfType(myFixture.file, SolFunctionDefinition::class.java).map { it.canonicalSignature() }
  }

  fun testAbiNames() = assertEquals(
    listOf(
      "f(uint256,int256,address,address,uint8,uint256,(uint256,address),bytes32[2],string,bytes[])",
      "receive()",
      "fallback()",
    ),
    signatures("""
      type Price is uint;
      enum E { A }
      interface I {}
      contract C {
          struct S { uint a; I i; }
          function f(uint a, int b, address payable c, I d, E e, Price p, S memory s, bytes32[2] memory h,
                     string calldata t, bytes[] memory u) external {}
          receive() external payable {}
          fallback(bytes calldata) external returns (bytes memory) {}
      }
    """)
  )

  fun testUnknownTypeHasNoSignature() = assertEquals(
    listOf(null, "g((uint256,(uint256,C.S[])[]))"),
    signatures("""
      contract C {
          struct S { uint a; S[] children; }
          struct T { uint b; S[] items; }
          function f(Missing m) internal {}
          function g(T memory t) internal {}
      }
    """)
  )
}
