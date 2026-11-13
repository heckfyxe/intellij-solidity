package me.serce.solidity.ide.inspections

class CheatcodeWithoutCallInspectionTest : SolInspectionsTestBase(CheatcodeWithoutCallInspection()) {
  private val prelude = """
    interface Vm {
        function expectRevert() external;
        function expectRevert(bytes4) external;
        function prank(address) external;
        function deal(address, uint256) external;
    }
    library Math {
        function half(uint256 x) internal pure returns (uint256) { return x / 2; }
        function ext(uint256 x) public pure returns (uint256) { return x; }
    }
    contract Vault {
        error Empty();
        uint256 public total;
        function withdraw(uint256 amount) external { if (amount > total) revert Empty(); }
    }
  """.trimIndent()

  private fun warn(cheatcode: String, code: String) =
    "/*@warning descr=\"vm.$cheatcode is not followed by an external call\"@*/$code/*@/warning@*/"

  fun testExternalCallsAreFine() = checkByText("""
    $prelude
    contract VaultTest {
        Vm constant vm = Vm(address(0));
        Vault vault;
        using Math for uint256;

        function test_direct() public {
            vm.expectRevert(Vault.Empty.selector);
            vault.withdraw(1);
        }

        function test_cheatcodesInBetween() public {
            vm.prank(address(1));
            vm.expectRevert();
            vault.withdraw(1);
        }

        function test_lowLevel() public {
            vm.expectRevert();
            (bool ok, ) = address(vault).call("");
            ok;
        }

        function test_deploy() public {
            vm.expectRevert();
            new Vault();
        }

        function test_this() public {
            vm.expectRevert();
            this.helper();
        }

        function test_getter() public {
            vm.prank(address(1));
            uint256 t = vault.total();
            t;
        }

        function test_internalHelperMakesTheCall() public {
            vm.expectRevert();
            _withdraw(1);
        }

        function test_publicLibraryFunction() public {
            vm.expectRevert();
            Math.ext(1);
        }

        function test_unresolved() public {
            vm.expectRevert();
            unknownThing.go();
        }

        function test_assembly() public {
            vm.expectRevert();
            assembly { pop(call(gas(), 0, 0, 0, 0, 0, 0)) }
        }

        function test_virtualHelper() public {
            vm.expectRevert();
            _hook();
        }

        function test_nested() public {
            if (true) {
                vm.expectRevert();
            }
            vault.withdraw(1);
        }

        function helper() external {}
        function _withdraw(uint256 a) internal { vault.withdraw(a); }
        function _hook() internal virtual { uint256 x = 1; x; }
    }
  """)

  fun testReportsWhenNoExternalCallFollows() = checkByText("""
    $prelude
    contract VaultTest {
        Vm constant vm = Vm(address(0));
        Vault vault;
        using Math for uint256;

        function test_last() public {
            vault.withdraw(1);
            ${warn("expectRevert", "vm.expectRevert()")};
        }

        function test_internalOnly() public {
            ${warn("expectRevert", "vm.expectRevert(Vault.Empty.selector)")};
            _check(1);
            vault.withdraw(1);
        }

        function test_arithmetic() public {
            uint256 zero = 0;
            ${warn("expectRevert", "vm.expectRevert()")};
            uint256 x = 1 / zero;
            x;
        }

        function test_internalLibrary() public {
            ${warn("expectRevert", "vm.expectRevert()")};
            uint256(3).half();
        }

        function test_prankWithoutCall() public {
            ${warn("prank", "vm.prank(address(1))")};
            vm.deal(address(1), 1);
        }

        function test_prankThenRequire() public {
            ${warn("prank", "vm.prank(address(1))")};
            require(Math.half(4) == 2, "half");
        }

        function _check(uint256 a) internal pure { require(a > 1, "small"); }
    }
  """)

  fun testOnlyInTestContracts() = checkByText("""
    $prelude
    contract Helper {
        Vm constant vm = Vm(address(0));
        function setUp() public {
            vm.expectRevert();
        }
    }
  """)
}
