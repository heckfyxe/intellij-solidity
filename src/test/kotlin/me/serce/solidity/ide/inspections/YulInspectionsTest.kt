package me.serce.solidity.ide.inspections

import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

/** Name resolution inspections inside inline assembly. */
class YulInspectionsTest : SolTestBase() {
  private fun check(@Language("Solidity") code: String) {
    myFixture.configureByText("main.sol", code)
    myFixture.enableInspections(
      ResolveNameInspection::class.java,
      NoReturnInspection::class.java,
      UnusedElementInspection::class.java,
    )
    myFixture.checkHighlighting(true, false, true)
  }

  fun testUndefinedYulNames() = check("""
    contract C {
        function f() public {
            assembly {
                let x := add(<warning descr="'y' is undefined">y</warning>, 1)
                mstore(0, x)
                <warning descr="Function 'nope' is undefined">nope</warning>(x)
                function g() -> r { r := <warning descr="'x' is undefined">x</warning> }
                pop(g())
            }
        }
    }
  """)

  fun testAllBuiltinsAreKnown() {
    val calls = me.serce.solidity.lang.resolve.YulBuiltins.all.joinToString("\n") { b ->
      val args = b.params.joinToString(", ") { "0" }
      if (b.returns) "pop(${b.name}($args))" else "${b.name}($args)"
    }
    check("""
      contract C {
          function f() public {
              assembly {
                  $calls
                  let v := verbatim_1i_1o(hex"600202", 1)
                  pop(v)
              }
          }
      }
    """)
  }

  fun testReturnVariableAssignedInNestedYulBlock() = check("""
    contract C {
        function f(uint a) public pure returns (uint r) {
            assembly {
                switch a
                case 0 { r := 1 }
                default { r := 2 }
            }
        }
    }
  """)

  fun testSolidityVariablesUsedOnlyInAssemblyAreUsed() = check("""
    contract C {
        uint private counter;
        uint private constant STEP = 2;
        function bump(uint by) external returns (uint result) {
            uint extra = 1;
            assembly {
                result := add(add(sload(counter.slot), by), add(extra, STEP))
                sstore(counter.slot, result)
            }
        }
    }
  """)

  fun testSoladyStyleLibrary() = check("""
    // SPDX-License-Identifier: MIT
    pragma solidity ^0.8.4;

    library SafeTransferLib {
        error ETHTransferFailed();
        error TransferFromFailed();

        uint256 internal constant GAS_STIPEND_NO_GRIEF = 100000;

        function safeTransferETH(address to, uint256 amount) internal {
            /// @solidity memory-safe-assembly
            assembly {
                if iszero(call(gas(), to, amount, codesize(), 0x00, codesize(), 0x00)) {
                    mstore(0x00, 0xb12d13eb) // `ETHTransferFailed()`.
                    revert(0x1c, 0x04)
                }
            }
        }

        function safeTransferFrom(address token, address from, address to, uint256 amount) internal {
            assembly ("memory-safe") {
                let m := mload(0x40)
                mstore(0x60, amount)
                mstore(0x40, to)
                mstore(0x2c, shl(96, from))
                mstore(0x0c, 0x23b872dd000000000000000000000000)
                let success := call(gas(), token, 0, 0x1c, 0x64, 0x00, 0x20)
                if iszero(and(eq(mload(0x00), 1), success)) {
                    if iszero(lt(or(iszero(extcodesize(token)), returndatasize()), success)) {
                        mstore(0x00, 0x7939f424) // `TransferFromFailed()`.
                        revert(0x1c, 0x04)
                    }
                }
                mstore(0x60, 0)
                mstore(0x40, m)
            }
        }

        function balanceOf(address token, address account) internal view returns (uint256 amount) {
            assembly {
                mstore(0x14, account)
                mstore(0x00, 0x70a08231000000000000000000000000)
                amount :=
                    mul(
                        mload(0x20),
                        and(gt(returndatasize(), 0x1f), staticcall(gas(), token, 0x10, 0x24, 0x20, 0x20))
                    )
            }
        }

        function toHexString(uint256 value) internal pure returns (string memory result) {
            assembly ("memory-safe") {
                function hexDigit(d) -> c {
                    c := byte(d, "0123456789abcdef")
                }
                result := add(mload(0x40), 0x80)
                mstore(0x40, add(result, 0x20))
                mstore(result, 0)
                let end := result
                for { let temp := value } 1 {} {
                    result := sub(result, 1)
                    mstore8(result, hexDigit(add(and(temp, 15), 16)))
                    temp := shr(4, temp)
                    if iszero(temp) { break }
                }
                let length := sub(end, result)
                result := sub(result, 0x20)
                mstore(result, length)
            }
        }

        function sum(uint256[] calldata xs) internal pure returns (uint256 total) {
            assembly {
                let end := add(xs.offset, shl(5, xs.length))
                for { let i := xs.offset } lt(i, end) { i := add(i, 0x20) } {
                    total := add(total, calldataload(i))
                }
                switch gt(total, GAS_STIPEND_NO_GRIEF)
                case 1 { total := GAS_STIPEND_NO_GRIEF }
                default {}
            }
        }

        function maxOf(uint256 x, uint256 y) internal pure returns (uint256 z) {
            assembly {
                function max(a, b) -> c {
                    c := xor(a, mul(xor(a, b), gt(b, a)))
                    if iszero(c) { leave }
                }
                z := max(x, y)
            }
        }
    }

    contract Vault {
        uint256 private _count;
        mapping(address => uint256) private _balances;
        address private immutable _token;

        constructor(address token) {
            _token = token;
        }

        function increment() external returns (uint256 newCount) {
            assembly {
                newCount := add(sload(_count.slot), 1)
                sstore(_count.slot, newCount)
            }
        }

        function stored(address a) external view returns (uint256 r) {
            assembly {
                mstore(0x00, a)
                mstore(0x20, _balances.slot)
                r := sload(keccak256(0x00, 0x40))
            }
        }

        function codeHead() external view returns (bytes32 h) {
            assembly {
                codecopy(0, 0, 32)
                h := mload(0)
            }
        }

        function run(address to, uint256[] calldata xs) external payable returns (string memory) {
            SafeTransferLib.safeTransferETH(to, msg.value);
            SafeTransferLib.safeTransferFrom(_token, msg.sender, to, SafeTransferLib.balanceOf(_token, msg.sender));
            return SafeTransferLib.toHexString(SafeTransferLib.maxOf(SafeTransferLib.sum(xs), 1));
        }
    }
  """)
}
