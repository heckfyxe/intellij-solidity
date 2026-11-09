package me.serce.solidity.ide.usage

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolStateVariableDeclaration
import me.serce.solidity.lang.psi.SolVariableDeclaration
import me.serce.solidity.utils.SolTestBase

class SolUseScopeTest : SolTestBase() {

  /** Only the declaring file and files importing it, directly or not, can see a declaration. */
  fun testDeclarationIsSearchedInImportingFilesOnly() {
    val base = myFixture.addFileToProject("src/Base.sol", "contract Base { address public owner; }")
    val child = myFixture.addFileToProject("src/Child.sol", "import \"./Base.sol\"; contract Child is Base { function f() public view returns (address) { return owner; } }")
    val grandChild = myFixture.addFileToProject("src/GrandChild.sol", "import \"./Child.sol\"; contract GrandChild is Child { function g() public view returns (address) { return owner; } }")
    val unrelated = myFixture.addFileToProject("lib/Ownable.sol", "contract Ownable { address public owner; function o() public view returns (address) { return owner; } }")
    // mentions Base.sol in a string but imports something else
    val lookalike = myFixture.addFileToProject("src/Lookalike.sol", "import \"../lib/Ownable.sol\"; contract L { string s = \"Base.sol\"; }")

    val owner = PsiTreeUtil.findChildOfType(base, SolStateVariableDeclaration::class.java)!!
    val scope = owner.useScope as GlobalSearchScope
    assertTrue(scope.contains(base.virtualFile))
    assertTrue(scope.contains(child.virtualFile))
    assertTrue(scope.contains(grandChild.virtualFile))
    assertFalse(scope.contains(unrelated.virtualFile))
    assertFalse(scope.contains(lookalike.virtualFile))

    val usages = myFixture.findUsages(owner).map { it.file!!.name }.sorted()
    assertEquals(listOf("Child.sol", "GrandChild.sol"), usages)
  }

  fun testLocalVariableIsSearchedInItsFunction() {
    val file = myFixture.addFileToProject("src/Local.sol", "contract C { function f() public pure returns (uint) { uint x = 1; return x; } }")
    val local = PsiTreeUtil.findChildOfType(file, SolVariableDeclaration::class.java)!!
    assertTrue(local.useScope is LocalSearchScope)
    assertEquals(1, myFixture.findUsages(local).size)
  }
}
