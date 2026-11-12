package me.serce.solidity.ide.typing

import com.intellij.openapi.actionSystem.IdeActions
import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

class SolSmartEnterTest : SolTestBase() {
  private fun doTest(@Language("Solidity") before: String, @Language("Solidity") after: String) =
    checkEditorAction(before, after, IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)

  private fun inFunction(body: String) =
    "contract A {\n    function f() public {\n" + body.trimIndent().prependIndent("        ") + "\n    }\n}"

  private fun doFunctionTest(before: String, after: String) = doTest(inFunction(before), inFunction(after))

  // semicolons

  fun testCallStatement() = doFunctionTest(
    "foo(1)<caret>",
    """
      foo(1);
      <caret>
    """
  )

  fun testCaretInTheMiddleOfStatement() = doFunctionTest(
    "foo(<caret>1)",
    """
      foo(1);
      <caret>
    """
  )

  fun testVariableDeclaration() = doFunctionTest(
    "uint x = 1<caret>",
    """
      uint x = 1;
      <caret>
    """
  )

  fun testAssignmentBeforeNextStatement() = doFunctionTest(
    """
      x = 1<caret>
      y = 2;
    """,
    """
      x = 1;
      <caret>
      y = 2;
    """
  )

  fun testReturn() = doFunctionTest(
    "return x<caret>",
    """
      return x;
      <caret>
    """
  )

  fun testBareReturn() = doFunctionTest(
    "return<caret>",
    """
      return;
      <caret>
    """
  )

  fun testEmit() = doFunctionTest(
    "emit E(1<caret>",
    """
      emit E(1);
      <caret>
    """
  )

  fun testRevert() = doFunctionTest(
    "revert E(<caret>",
    """
      revert E();
      <caret>
    """
  )

  fun testBreak() = doFunctionTest(
    "break<caret>",
    """
      break;
      <caret>
    """
  )

  fun testContinue() = doFunctionTest(
    "continue<caret>",
    """
      continue;
      <caret>
    """
  )

  fun testTrailingComment() = doFunctionTest(
    "foo(1) // call<caret>",
    """
      foo(1); // call
      <caret>
    """
  )

  fun testCallOptions() = doFunctionTest(
    "(bool ok, ) = to.call{value: amount}(\"\"<caret>",
    """
      (bool ok, ) = to.call{value: amount}("");
      <caret>
    """
  )

  fun testStructLiteral() = doFunctionTest(
    "S memory s = S({a: 1, b: 2}<caret>",
    """
      S memory s = S({a: 1, b: 2});
      <caret>
    """
  )

  fun testSecondStatementOnLine() = doFunctionTest(
    "a = 1; b = 2<caret>",
    """
      a = 1; b = 2;
      <caret>
    """
  )

  fun testCompleteStatementOnlyMovesCaret() = doFunctionTest(
    """
      foo(<caret>1);
      bar();
    """,
    """
      foo(1);
      <caret>
      bar();
    """
  )

  fun testMultilineCallIsNotClosedEarly() = doFunctionTest(
    """
      foo(a,<caret>
          b)
    """,
    """
      foo(a,
          b);
      <caret>
    """
  )

  fun testUnclosedCallBeforeNextStatement() = doFunctionTest(
    """
      foo(1<caret>
      bar();
    """,
    """
      foo(1);
      <caret>
      bar();
    """
  )

  fun testNestedCall() = doFunctionTest(
    "foo(bar(1<caret>",
    """
      foo(bar(1));
      <caret>
    """
  )

  fun testIndexAccess() = doFunctionTest(
    "x = a[i<caret>",
    """
      x = a[i];
      <caret>
    """
  )

  fun testStateVariable() = doTest(
    """
      contract A {
          uint public x<caret>
      }
    """,
    """
      contract A {
          uint public x;
          <caret>
      }
    """
  )

  fun testEvent() = doTest(
    """
      contract A {
          event E(uint a<caret>
      }
    """,
    """
      contract A {
          event E(uint a);
          <caret>
      }
    """
  )

  fun testError() = doTest(
    """
      contract A {
          error E(uint a)<caret>
      }
    """,
    """
      contract A {
          error E(uint a);
          <caret>
      }
    """
  )

  fun testUsing() = doTest(
    """
      contract A {
          using L for uint<caret>
      }
    """,
    """
      contract A {
          using L for uint;
          <caret>
      }
    """
  )

  fun testUsingWithBraces() = doTest(
    "using {add as +} for Fixed global<caret>",
    """
      using {add as +} for Fixed global;
      <caret>
    """
  )

  fun testImport() = doTest(
    "import \"./a.sol\"<caret>",
    """
      import "./a.sol";
      <caret>
    """
  )

  fun testNamedImport() = doTest(
    "import {A, B} from \"./a.sol\"<caret>",
    """
      import {A, B} from "./a.sol";
      <caret>
    """
  )

  fun testPragma() = doTest(
    "pragma solidity ^0.8.0<caret>",
    """
      pragma solidity ^0.8.0;
      <caret>
    """
  )

  fun testStructMember() = doTest(
    """
      struct S {
          uint a<caret>
      }
    """,
    """
      struct S {
          uint a;
          <caret>
      }
    """
  )

  fun testEnumValueGetsNoSemicolon() = doTest(
    """
      enum E {
          A,<caret>
          B
      }
    """,
    """
      enum E {
          A,
          <caret>
          B
      }
    """
  )

  fun testDoWhile() = doFunctionTest(
    """
      do {
          x++;
      } while (x < 10<caret>
    """,
    """
      do {
          x++;
      } while (x < 10);
      <caret>
    """
  )

  fun testYulIsLeftAlone() = doFunctionTest(
    """
      assembly {
          let x := add(1, 2)<caret>
      }
    """,
    """
      assembly {
          let x := add(1, 2)
          <caret>
      }
    """
  )

  // conditions and bodies

  fun testIf() = doFunctionTest(
    "if (x)<caret>",
    """
      if (x) {
          <caret>
      }
    """
  )

  fun testIfWithUnclosedCondition() = doFunctionTest(
    "if (x > 1<caret>",
    """
      if (x > 1) {
          <caret>
      }
    """
  )

  fun testIfWithUnclosedConditionBeforeBrace() = doFunctionTest(
    "if (x {<caret>}",
    """
      if (x) {
          <caret>
      }
    """
  )

  fun testIfWithBody() = doFunctionTest(
    """
      if (x<caret>) {
          foo();
      }
    """,
    """
      if (x) {
          <caret>
          foo();
      }
    """
  )

  fun testBracelessIfBody() = doFunctionTest(
    "if (x) foo()<caret>",
    """
      if (x) foo();
      <caret>
    """
  )

  fun testBracelessIfBodyOnNextLine() = doFunctionTest(
    """
      if (x)<caret>
          foo();
    """,
    """
      if (x)
          foo();
      <caret>
    """
  )

  fun testElse() = doFunctionTest(
    """
      if (x) {
          foo();
      } else<caret>
    """,
    """
      if (x) {
          foo();
      } else {
          <caret>
      }
    """
  )

  fun testElseIf() = doFunctionTest(
    """
      if (x) {
          foo();
      } else if (y<caret>
    """,
    """
      if (x) {
          foo();
      } else if (y) {
          <caret>
      }
    """
  )

  fun testWhile() = doFunctionTest(
    "while (i < n<caret>",
    """
      while (i < n) {
          <caret>
      }
    """
  )

  fun testFor() = doFunctionTest(
    "for (uint i = 0; i < n; i++<caret>",
    """
      for (uint i = 0; i < n; i++) {
          <caret>
      }
    """
  )

  fun testForWithClosedHeader() = doFunctionTest(
    "for (uint i = 0; i < n; i++)<caret>",
    """
      for (uint i = 0; i < n; i++) {
          <caret>
      }
    """
  )

  fun testUnchecked() = doFunctionTest(
    "unchecked<caret>",
    """
      unchecked {
          <caret>
      }
    """
  )

  fun testFunction() = doTest(
    """
      contract A {
          function f(uint a) public<caret>
      }
    """,
    """
      contract A {
          function f(uint a) public {
              <caret>
          }
      }
    """
  )

  fun testFunctionWithUnclosedParameters() = doTest(
    """
      contract A {
          function f(uint a<caret>
      }
    """,
    """
      contract A {
          function f(uint a) {
              <caret>
          }
      }
    """
  )

  fun testMultilineFunctionHeader() = doTest(
    """
      contract A {
          function f()<caret>
              public
              returns (uint)
      }
    """,
    """
      contract A {
          function f()
              public
              returns (uint) {
              <caret>
          }
      }
    """
  )

  fun testFunctionWithSemicolonIsLeftBodyless() = doTest(
    """
      abstract contract A {
          function f() public virtual;<caret>
      }
    """,
    """
      abstract contract A {
          function f() public virtual;
          <caret>
      }
    """
  )

  fun testInterfaceFunctionGetsSemicolon() = doTest(
    """
      interface I {
          function f() external returns (uint)<caret>
      }
    """,
    """
      interface I {
          function f() external returns (uint);
          <caret>
      }
    """
  )

  fun testFunctionWithExistingEmptyBody() = doTest(
    """
      contract A {
          function f() public<caret> {
          }
      }
    """,
    """
      contract A {
          function f() public {
              <caret>
          }
      }
    """
  )

  fun testConstructor() = doTest(
    """
      contract A {
          constructor(uint a<caret>
      }
    """,
    """
      contract A {
          constructor(uint a) {
              <caret>
          }
      }
    """
  )

  fun testModifier() = doTest(
    """
      contract A {
          modifier onlyOwner()<caret>
      }
    """,
    """
      contract A {
          modifier onlyOwner() {
              <caret>
          }
      }
    """
  )

  fun testFunctionTypeVariable() = doTest(
    """
      contract A {
          function (uint) external returns (uint) callback<caret>
      }
    """,
    """
      contract A {
          function (uint) external returns (uint) callback;
          <caret>
      }
    """
  )

  fun testContract() = doTest(
    "contract A is B, C<caret>",
    """
      contract A is B, C {
          <caret>
      }
    """
  )

  fun testAbstractContract() = doTest(
    "abstract contract A<caret>",
    """
      abstract contract A {
          <caret>
      }
    """
  )

  fun testInterface() = doTest(
    "interface I<caret>",
    """
      interface I {
          <caret>
      }
    """
  )

  fun testLibrary() = doTest(
    "library L<caret>",
    """
      library L {
          <caret>
      }
    """
  )

  fun testStruct() = doTest(
    """
      contract A {
          struct S<caret>
      }
    """,
    """
      contract A {
          struct S {
              <caret>
          }
      }
    """
  )

  fun testEnum() = doTest(
    "enum E<caret>",
    """
      enum E {
          <caret>
      }
    """
  )

  fun testInterfaceFunctionWithUnclosedParameters() = doTest(
    """
      interface I {
          function f(uint a<caret>
      }
    """,
    """
      interface I {
          function f(uint a);
          <caret>
      }
    """
  )

  fun testIfWithBraceOnNextLine() = doFunctionTest(
    """
      if (x)<caret>
      {
      }
    """,
    """
      if (x)
      {
          <caret>
      }
    """
  )

  fun testEmptyLine() = doFunctionTest(
    """
      foo();
      <caret>
    """,
    """
      foo();

      <caret>
    """
  )
}
