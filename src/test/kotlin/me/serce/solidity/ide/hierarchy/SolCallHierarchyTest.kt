package me.serce.solidity.ide.hierarchy

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.codeInsight.hierarchy.HierarchyViewTestFixture
import me.serce.solidity.ide.SolCallHierarchyTreeStructure
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolModifierDefinition
import me.serce.solidity.utils.SolTestBase

class SolCallHierarchyTest : SolTestBase() {
  private val code = """
    contract A {
        uint x = helper();
        modifier onlyOwner() { check(); _; }
        function check() internal view {}
        function helper() internal pure returns (uint) { return 1; }
        function set(uint v) public onlyOwner { x = v + helper() + helper(); }
        function run() external { set(1); check(); }
        function rec(uint n) internal { if (n > 0) rec(n - 1); }
    }
  """.trimIndent()

  private fun function(name: String) =
    PsiTreeUtil.findChildrenOfType(myFixture.file, SolFunctionDefinition::class.java).first { it.name == name }

  fun testCallers() {
    InlineFile(code)
    HierarchyViewTestFixture.doHierarchyTest(
      SolCallHierarchyTreeStructure(project, function("helper"), callers = true),
      """
      <node text="helper()  A" base="true">
        <node text="x  A"/>
        <node text="set(uint)  A  (2 usages)">
          <node text="run()  A"/>
        </node>
      </node>
      """.trimIndent()
    )
  }

  fun testCallees() {
    InlineFile(code)
    HierarchyViewTestFixture.doHierarchyTest(
      SolCallHierarchyTreeStructure(project, function("run"), callers = false),
      """
      <node text="run()  A" base="true">
        <node text="set(uint)  A">
          <node text="modifier onlyOwner  A">
            <node text="check()  A"/>
          </node>
          <node text="helper()  A  (2 usages)"/>
        </node>
        <node text="check()  A"/>
      </node>
      """.trimIndent()
    )
  }

  fun testModifierCallers() {
    InlineFile(code)
    val modifier = PsiTreeUtil.findChildOfType(myFixture.file, SolModifierDefinition::class.java)!!
    HierarchyViewTestFixture.doHierarchyTest(
      SolCallHierarchyTreeStructure(project, modifier, callers = true),
      """
      <node text="modifier onlyOwner  A" base="true">
        <node text="set(uint)  A">
          <node text="run()  A"/>
        </node>
      </node>
      """.trimIndent()
    )
  }

  fun testRecursionStops() {
    InlineFile(code)
    HierarchyViewTestFixture.doHierarchyTest(
      SolCallHierarchyTreeStructure(project, function("rec"), callers = false),
      """
      <node text="rec(uint)  A" base="true">
        <node text="rec(uint)  A"/>
      </node>
      """.trimIndent()
    )
  }

  fun testCallersThroughOverrides() {
    InlineFile("""
      contract A {
          function f() public virtual {}
          function g() public { f(); }
      }
      contract B is A {
          function f() public virtual override { super.f(); }
      }
      contract C is B {
          function f() public override { A.f(); }
          function h() public { f(); }
      }
      contract User {
          function u(A a) public { a.f(); }
      }
    """.trimIndent())
    val fInB = PsiTreeUtil.findChildrenOfType(myFixture.file, SolFunctionDefinition::class.java)
      .first { it.name == "f" && it.text.contains("super") }
    HierarchyViewTestFixture.doHierarchyTest(
      SolCallHierarchyTreeStructure(project, fInB, callers = true),
      """
      <node text="f()  B" base="true">
        <node text="g()  A"/>
        <node text="u(A)  User"/>
      </node>
      """.trimIndent()
    )
  }
}
