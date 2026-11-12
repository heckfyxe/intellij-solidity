package me.serce.solidity.ide.structure

import me.serce.solidity.ide.SolStructureViewModel
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.psi.SolNamedElement
import me.serce.solidity.utils.SolTestBase

class SolStructureViewTest : SolTestBase() {
  fun testFileLevelEnumIsVisible() {
    val file = InlineFile(
      """
        enum TestEnum {A, B}
        contract C {}
      """.trimIndent()
    )
    val model = SolStructureViewModel(myFixture.editor, file.psiFile as SolidityFile)
    val root = model.root
    val names = root.children.mapNotNull { it?.value }
      .filterIsInstance<SolNamedElement>()
      .map { it.name }
    assertEquals(listOf("TestEnum", "C"), names)
  }

  fun testFileLevelDeclarationsInSourceOrder() {
    val file = InlineFile(
      """
        uint256 constant MAX = 10;
        type Price is uint128;
        event Deposited(address indexed who, uint256 amount);
        error Unauthorized(address who);
        function helper() pure returns (uint256) { return 1; }
        struct S { uint a; }
        contract C {
            event Inner();
            uint total;
            function f() public {}
        }
      """.trimIndent()
    )
    val model = SolStructureViewModel(myFixture.editor, file.psiFile as SolidityFile)
    val root = model.root
    val texts = root.children.map { it.presentation.presentableText }
    assertEquals(
      listOf("MAX", "Price", "Deposited(address indexed who, uint256 amount)", "Unauthorized(address who)", "helper() returns (uint256)", "S", "C"),
      texts.map { it?.trim() }
    )
    val contract = root.children.last()
    assertEquals(listOf("Inner()", "total : uint", "f()"), contract.children.map { it.presentation.presentableText?.trim() })
  }
}
