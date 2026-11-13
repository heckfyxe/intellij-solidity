package me.serce.solidity.ide.usage

class SolYulUsageTest : SolUsageTestBase() {
  fun testYulVariable() = doTest("""
        contract C {
            function f() public {
                assembly {
                    let m := mload(0x40)
                      //^
                    mstore(m, 1)
                    if m { m := add(m, 0x20) }
                    function g(m) -> r { r := m }
                }
            }
        }
  """, 4)

  fun testYulFunction() = doTest("""
        contract C {
            function f() public {
                assembly {
                    let r := double(2)
                    function double(v) -> w { w := add(v, double(0)) }
                               //^
                    { pop(double(r)) }
                }
            }
        }
  """, 3)

  fun testYulFunctionParameter() = doTest("""
        contract C {
            function f() public {
                assembly {
                    function double(v) -> w { w := add(v, v) }
                                  //^
                }
            }
        }
  """, 2)

  fun testSolidityReturnVariableUsedInAssembly() = doTest("""
        contract C {
            function f() public returns (uint result) {
                                              //^
                assembly {
                    result := 1
                    if result { result := add(result, 1) }
                }
            }
        }
  """, 4)
}
