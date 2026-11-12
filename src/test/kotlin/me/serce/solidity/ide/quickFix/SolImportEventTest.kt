package me.serce.solidity.ide.quickFix

import me.serce.solidity.ide.inspections.ResolveNameInspection

class SolImportEventTest : SolQuickFixTestBase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(ResolveNameInspection().javaClass)
  }

  fun testImportFileLevelEvent() {
    InlineFile(code = "event Deposited(address who, uint256 amount);", name = "Events.sol")
    val main = """
      contract C {
        function f() public {
          emit Deposited(msg.sender, 1);
        }
      }
    """.trimIndent()
    checkQuickFix(main, "import \"./Events.sol\";\n\n$main")
  }

  fun testImportFileLevelError() {
    InlineFile(code = "error Unauthorized(address who);", name = "Errors.sol")
    val main = """
      contract C {
        function f() public {
          revert Unauthorized(msg.sender);
        }
      }
    """.trimIndent()
    checkQuickFix(main, "import \"./Errors.sol\";\n\n$main")
  }

  fun testSelectiveImportOfEventResolves() {
    InlineFile(code = "event Deposited(address who, uint256 amount);", name = "Events.sol")
    assertNoQuickFix("""
      import {Deposited} from "./Events.sol";

      contract C {
        function f() public {
          emit Deposited(msg.sender, 1);
        }
      }
    """)
  }

  fun testUnresolvedSelectiveImportNamesTheSymbol() {
    InlineFile(code = "event Deposited(address who, uint256 amount);", name = "Events.sol")
    InlineFile("import {Missing} from \"./Events.sol\";")
    val warnings = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WARNING).map { it.description }
    assertTrue(warnings.toString(), "'Missing' cannot be resolved" in warnings)
  }

  fun testAddsToExistingSelectiveImport() {
    InlineFile(code = "event Deposited(address who);\nevent Withdrawn(address who);", name = "Events.sol")
    val body = """

      contract C {
        function f() public {
          emit Deposited(msg.sender);
          emit Withdrawn(msg.sender);
        }
      }
    """.trimIndent()
    checkQuickFix("import {Deposited} from \"./Events.sol\";\n$body", "import {Deposited, Withdrawn} from \"./Events.sol\";\n$body")
  }
}
