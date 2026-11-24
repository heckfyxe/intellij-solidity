package me.serce.solidity.lang.completion

class SolIdentifierCompletionTest : SolCompletionTestBase() {
  fun testVariableCompletion() = checkCompletion(hashSetOf("Foo", "FooBar"), """
    contract Foo {}
    contract FooBar {}

    contract A {
        function f() {
            Fo/*caret*/ kk;
        }
    }

  """)

  fun testCamelHumpTypeCompletionInTypeNameCompleter() {
    InlineFile("contract FooBar {}", "external.sol")

    InlineFile(
      """
      contract A {
        function f() {
          /*caret*/
        }
      }
      """
    ).withCaret()

    val position = myFixture.file.findElementAt(myFixture.caretOffset)
    checkNotNull(position)
    val variants = SolCompleter.completeTypeName(position, prefix = "FB", invocationCount = 1)
      .map { it.lookupString }
      .toSet()
    assertTrue("CamelHump prefix should match FooBar in type completion", variants.contains("FooBar"))
  }

  fun testCompletionWithImport() {
    InlineFile(
      code = "contract test {}", name = "test.sol"
    )

    checkResultAfterCompletion(
      """contract A is tes/*caret*/{}""", """import {test} from "./test.sol";

contract A is test{}"""
    )
  }

  fun testCompletionImportsFileLevelStruct() {
    InlineFile(code = "struct Position { uint256 x; }", name = "types.sol")

    checkResultAfterCompletion(
      """contract A { Positi/*caret*/ p; }""", """import {Position} from "./types.sol";

contract A { Position p; }"""
    )
  }

  fun testEventsAndErrorsKeepTheirIconsInTypeCompletion() {
    InlineFile("event Deposited(); error Failed(); contract Vault {}", "external.sol")
    InlineFile("contract A { function f() { /*caret*/ } }").withCaret()

    val position = checkNotNull(myFixture.file.findElementAt(myFixture.caretOffset))
    val icons = SolCompleter.completeTypeName(position).associate { element ->
      element.lookupString to com.intellij.codeInsight.lookup.LookupElementPresentation().also { element.renderElement(it) }.icon
    }
    assertEquals(me.serce.solidity.ide.SolidityIcons.EVENT, icons["Deposited"])
    assertEquals(me.serce.solidity.ide.SolidityIcons.ERROR, icons["Failed"])
    assertEquals(me.serce.solidity.ide.SolidityIcons.CONTRACT, icons["Vault"])
  }

  fun testCompletionWithImportRecursion() {
    InlineFile(
      code = """contract test {}""", name = "test.sol"
    )

    InlineFile(
      code = """import "./rec2.sol"; contract rec1 {}""", name = "rec1.sol"
    )

    InlineFile(
      code = """import "./rec1.sol"; contract rec2 {}""", name = "rec2.sol"
    )

    checkResultAfterCompletion(
      """import "./rec1.sol"; contract A is tes/*caret*/{}""", """import "./rec1.sol";
import {test} from "./test.sol";

contract A is test{}"""
    )
  }

  fun testContractCompletionNotDuplicatedInBlock() {
    InlineFile(
      """
      contract Foo {}

      contract A {
        function run() {
          Fo/*caret*/ value;
        }
      }
      """
    ).withCaret()

    val variants = myFixture.completeBasic()
      .map { it.lookupString }
      .count { it == "Foo" }
    assertEquals(1, variants)
  }

  fun testGlobalTypeCompletionHiddenForImplicitInvocationWithoutPrefix() {
    InlineFile("contract ExternalType {}", "external.sol")

    InlineFile(
      """
      contract A {
        function run() {
          /*caret*/
        }
      }
      """
    ).withCaret()

    val position = myFixture.file.findElementAt(myFixture.caretOffset)
    checkNotNull(position)
    val variants = SolCompleter.completeLiteral(position, invocationCount = 0)
      .map { it.lookupString }
      .toSet()
    assertFalse("Implicit completion should not flood with project-wide type names", variants.contains("ExternalType"))
  }

  fun testGlobalTypeCompletionShownOnExplicitInvocationWithoutPrefix() {
    InlineFile("contract ExternalType {}", "external.sol")

    InlineFile(
      """
      contract A {
        function run() {
          /*caret*/
        }
      }
      """
    ).withCaret()

    val position = myFixture.file.findElementAt(myFixture.caretOffset)
    checkNotNull(position)
    val variants = SolCompleter.completeLiteral(position, invocationCount = 1)
      .map { it.lookupString }
      .toSet()
    assertTrue("Explicit completion should include project-wide type names", variants.contains("ExternalType"))
  }
}
