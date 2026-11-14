package me.serce.solidity.ide.generation

import com.intellij.ide.util.PropertiesComponent
import me.serce.solidity.lang.psi.SolNamedElement
import me.serce.solidity.utils.SolTestBase
import me.serce.solidity.settings.FormatterType
import org.intellij.lang.annotations.Language

class SolOverrideImplementHandlerTest : SolTestBase() {

  private fun doTest(handler: SolOverrideImplementHandler, @Language("Solidity") before: String, @Language("Solidity") after: String) {
    myFixture.configureByText("main.sol", before.trimIndent())
    assertTrue("handler should be available", handler.isValidFor(myFixture.editor, myFixture.file))
    handler.invoke(project, myFixture.editor, myFixture.file)
    // plain string comparison so a failure report contains both texts
    assertEquals(after.trimIndent(), myFixture.editor.document.text)
  }

  private fun candidateNames(handler: SolOverrideImplementHandler, @Language("Solidity") code: String): List<String?> {
    myFixture.configureByText("main.sol", code.trimIndent())
    val contract = handler.contractAt(myFixture.editor, myFixture.file) ?: return emptyList()
    return handler.candidates(contract).map(SolNamedElement::getName)
  }

  fun testOverrideStoreGeneratesSuperCall() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract SimpleStorage {
        function store(uint256 _x) public virtual {}
    }
    contract AddFiveStorage is SimpleStorage {
        <caret>
    }
    """,
    """
    contract SimpleStorage {
        function store(uint256 _x) public virtual {}
    }
    contract AddFiveStorage is SimpleStorage {
        function store(uint256 _x) public override {
            super.store(_x);
        }
    }
    """
  )

  fun testReturnsUsesReturn() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract A {
        function get() public view virtual returns (uint256) { return 1; }
    }
    contract B is A {
        <caret>
    }
    """,
    """
    contract A {
        function get() public view virtual returns (uint256) { return 1; }
    }
    contract B is A {
        function get() public view override returns (uint256) {
            return super.get();
        }
    }
    """
  )

  fun testExternalHasEmptyBody() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract A {
        function f(uint x) external virtual {}
    }
    contract B is A {
        <caret>
    }
    """,
    """
    contract A {
        function f(uint x) external virtual {}
    }
    contract B is A {
        function f(uint x) external override {
        }
    }
    """
  )

  fun testImplementInterface() = doTest(
    SolImplementMethodsHandler(),
    """
    interface I {
        function f() external;
    }
    contract C is I {
        <caret>
    }
    """,
    """
    interface I {
        function f() external;
    }
    contract C is I {
        function f() external override {
        }
    }
    """
  )

  fun testImplementGettersAsPublicStateVariables() = doTest(
    SolImplementMethodsHandler(),
    """
    pragma solidity ^0.8.20;
    struct Info { uint a; }
    interface I {
        function balanceOf(address owner) external view returns (uint256);
        function allowance(address owner, address spender) external view returns (uint256);
        function name() external view returns (string memory);
        function info() external view returns (Info memory);
        function ids() external view returns (uint256[] memory);
        function transfer(address to, uint256 amount) external returns (bool);
    }
    contract C is I {
        <caret>
    }
    """,
    """
    pragma solidity ^0.8.20;
    struct Info { uint a; }
    interface I {
        function balanceOf(address owner) external view returns (uint256);
        function allowance(address owner, address spender) external view returns (uint256);
        function name() external view returns (string memory);
        function info() external view returns (Info memory);
        function ids() external view returns (uint256[] memory);
        function transfer(address to, uint256 amount) external returns (bool);
    }
    contract C is I {
        mapping(address => uint256) public override balanceOf;
        mapping(address => mapping(address => uint256)) public override allowance;
        string public override name;

        function info() external view override returns (Info memory) {
        }

        function ids() external view override returns (uint256[] memory) {
        }

        function transfer(address to, uint256 amount) external override returns (bool) {
        }
    }
    """
  )

  fun testGetterVariablesOptionOff() {
    PropertiesComponent.getInstance(project).setValue("solidity.implement.getters.as.variables", false, true)
    try {
      doTest(
        SolImplementMethodsHandler(),
        """
        pragma solidity ^0.8.20;
        interface I {
            function balanceOf(address owner) external view returns (uint256);
        }
        contract C is I {
            <caret>
        }
        """,
        """
        pragma solidity ^0.8.20;
        interface I {
            function balanceOf(address owner) external view returns (uint256);
        }
        contract C is I {
            function balanceOf(address owner) external view override returns (uint256) {
            }
        }
        """
      )
    } finally {
      PropertiesComponent.getInstance(project).unsetValue("solidity.implement.getters.as.variables")
    }
  }

  fun testNoGetterVariablesWhenPragmaAllowsSolcBefore06() = doTest(
    SolImplementMethodsHandler(),
    """
    pragma solidity >=0.5.0;
    interface I {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C is I {
        <caret>
    }
    """,
    """
    pragma solidity >=0.5.0;
    interface I {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C is I {
        function balanceOf(address owner) external view override returns (uint256) {
        }
    }
    """
  )

  fun testNoOverrideKeywordBeforeSolc06() = doTest(
    SolImplementMethodsHandler(),
    """
    pragma solidity ^0.5.0;
    interface I {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C is I {
        <caret>
    }
    """,
    """
    pragma solidity ^0.5.0;
    interface I {
        function balanceOf(address owner) external view returns (uint256);
    }
    contract C is I {
        function balanceOf(address owner) external view returns (uint256) {
        }
    }
    """
  )

  fun testImplementSkipsGettersImplementedByVariables() {
    val names = candidateNames(
      SolImplementMethodsHandler(),
      """
      interface I {
          function balanceOf(address owner) external view returns (uint256);
          function ownerOf(uint256 id) external view returns (address);
          function tokens(uint256 index) external view returns (uint256);
          function name() external view returns (string memory);
          function approve(address to, uint256 id) external payable;
      }
      abstract contract Base is I {
          mapping(uint256 => address) public override ownerOf;
      }
      contract C is Base {
          mapping(address => uint256) public override balanceOf;
          uint256[] public override tokens;
          string internal name;
          <caret>
      }
      """
    )
    assertEquals(listOf("name", "approve"), names)
  }

  fun testMultipleBasesOverrideList() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract A {
        function f() public virtual {}
    }
    contract B {
        function f() public virtual {}
    }
    contract C is A, B {
        <caret>
    }
    """,
    """
    contract A {
        function f() public virtual {}
    }
    contract B {
        function f() public virtual {}
    }
    contract C is A, B {
        function f() public override(B, A) {
            super.f();
        }
    }
    """
  )

  fun testModifierStub() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract A {
        modifier m(uint x) virtual { _; }
    }
    contract B is A {
        <caret>
    }
    """,
    """
    contract A {
        modifier m(uint x) virtual { _; }
    }
    contract B is A {
        modifier m(uint x) override {
            _;
        }
    }
    """
  )

  fun testAlreadyOverriddenNotOffered() {
    val names = candidateNames(
      SolOverrideMethodsHandler(),
      """
      contract A {
          function f() public virtual {}
          function g() public virtual {}
      }
      contract B is A {
          function f() public override {}
          <caret>
      }
      """
    )
    assertEquals(listOf("g"), names)
  }

  fun testNonVirtualOfferedPrivateNot() {
    val names = candidateNames(
      SolOverrideMethodsHandler(),
      """
      contract A {
          function f() public {}
          function g() public virtual {}
          function h() private {}
      }
      contract B is A {
          <caret>
      }
      """
    )
    assertEquals(listOf("f", "g"), names)
  }

  fun testNonVirtualBaseIsMadeVirtual() = doTest(
    SolOverrideMethodsHandler(),
    """
    contract SimpleStorage {
        function retrieve() public view returns (uint256) { return 1; }
    }
    contract AddFiveStorage is SimpleStorage {
        <caret>
    }
    """,
    """
    contract SimpleStorage {
        function retrieve() public view virtual returns (uint256) { return 1; }
    }
    contract AddFiveStorage is SimpleStorage {
        function retrieve() public view override returns (uint256) {
            return super.retrieve();
        }
    }
    """
  )

  fun testNonVirtualBaseInOtherFileIsMadeVirtual() {
    myFixture.addFileToProject("SimpleStorage.sol", "contract SimpleStorage {\n    function store(uint256 _x) public {}\n}")
    doTest(
      SolOverrideMethodsHandler(),
      """
      import "./SimpleStorage.sol";
      contract AddFiveStorage is SimpleStorage {
          <caret>
      }
      """,
      """
      import "./SimpleStorage.sol";
      contract AddFiveStorage is SimpleStorage {
          function store(uint256 _x) public override {
              super.store(_x);
          }
      }
      """
    )
    val base = com.intellij.psi.PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("SimpleStorage.sol"))!!
    assertEquals("contract SimpleStorage {\n    function store(uint256 _x) public virtual {}\n}", base.text)
  }

  fun testImplementSkipsImplemented() {
    val names = candidateNames(
      SolImplementMethodsHandler(),
      """
      interface I {
          function f() external;
          function g() external;
      }
      abstract contract A is I {
          function f() external virtual override {}
      }
      contract B is A {
          <caret>
      }
      """
    )
    assertEquals(listOf("g"), names)
  }

  fun testNotAvailableOutsideContract() {
    myFixture.configureByText(
      "main.sol",
      """
      contract A { function f() public virtual {} }
      <caret>
      contract B is A {}
      """.trimIndent()
    )
    assertFalse(SolOverrideMethodsHandler().isValidFor(myFixture.editor, myFixture.file))
  }

  fun testIndentedWithoutIdeFormatter() = withFormatter(FormatterType.DISABLED) {
      doTest(
        SolOverrideMethodsHandler(),
        """
        contract A {
            function store(uint256 _x) public virtual {}
        }
        contract B is A {
            <caret>
        }
        """,
        """
        contract A {
            function store(uint256 _x) public virtual {}
        }
        contract B is A {
            function store(uint256 _x) public override {
                super.store(_x);
            }
        }
        """
      )
  }

  // Ctrl+O must react (with a hint) when bases exist but nothing can be overridden, instead of silently doing nothing
  fun testAvailableWhenNothingToOverride() {
    val before = """
      contract A {
          function f() public {}
      }
      contract B is A {
          function f() public override {}
          <caret>
      }
      """.trimIndent()
    myFixture.configureByText("main.sol", before)
    val handler = SolOverrideMethodsHandler()
    assertTrue(handler.isValidFor(myFixture.editor, myFixture.file))
    handler.invoke(project, myFixture.editor, myFixture.file)
    assertEquals(before.replace("<caret>", ""), myFixture.editor.document.text)
  }

  fun testNotAvailableWithoutBases() {
    myFixture.configureByText("main.sol", "contract A {\n    <caret>\n}")
    assertFalse(SolOverrideMethodsHandler().isValidFor(myFixture.editor, myFixture.file))
  }
}
