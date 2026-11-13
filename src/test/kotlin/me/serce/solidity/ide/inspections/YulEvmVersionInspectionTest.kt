package me.serce.solidity.ide.inspections

import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

class YulEvmVersionInspectionTest : SolTestBase() {
  override fun tearDown() {
    try {
      YulEvmVersionInspection.evmVersionForTests = null
    } finally {
      super.tearDown()
    }
  }

  private fun checkEvm(evm: String?, @Language("Solidity") code: String) {
    YulEvmVersionInspection.evmVersionForTests = { evm }
    myFixture.configureByText("main.sol", code)
    myFixture.enableInspections(YulEvmVersionInspection::class.java)
    myFixture.checkHighlighting(false, false, false)
  }

  fun testBuiltinsNewerThanTheEvmVersion() = checkEvm("shanghai", """
    contract C {
        function f() public {
            assembly {
                <error descr="'tstore' is only available for Cancun-compatible VMs (the EVM version is shanghai)">tstore</error>(0, 1)
                pop(<error descr="'blobbasefee' is only available for Cancun-compatible VMs (the EVM version is shanghai)">blobbasefee</error>())
                pop(<error descr="'clz' is only available for Osaka-compatible VMs (the EVM version is shanghai)">clz</error>(1))
                pop(prevrandao())
                pop(basefee())
                pop(chainid())
            }
        }
    }
  """)

  fun testDifficultyAndPrevrandao() = checkEvm("london", """
    contract C {
        function f() public {
            assembly {
                pop(difficulty())
                pop(<error descr="'prevrandao' is only available for Paris-compatible VMs (the EVM version is london), use 'difficulty' instead">prevrandao</error>())
            }
        }
    }
  """)

  fun testDifficultyRemovedInParis() = checkEvm("cancun", """
    contract C {
        function f() public {
            assembly {
                pop(<error descr="'difficulty' was replaced by 'prevrandao' in the paris EVM version (the EVM version is cancun)">difficulty</error>())
                tstore(0, tload(1))
                mcopy(0, 32, 32)
                pop(blobhash(0))
            }
        }
    }
  """)

  fun testForgeSpelling() = checkEvm("tangerine_whistle", """
    contract C {
        function f() public {
            assembly {
                pop(<error descr="'chainid' is only available for Istanbul-compatible VMs (the EVM version is tangerineWhistle)">chainid</error>())
            }
        }
    }
  """)

  fun testUnknownEvmVersionReportsNothing() = checkEvm(null, """
    contract C {
        function f() public {
            assembly {
                tstore(0, 1)
                pop(clz(1))
            }
        }
    }
  """)

  fun testEvmVersionInYulFile() {
    YulEvmVersionInspection.evmVersionForTests = { "paris" }
    myFixture.configureByText("main.yul", """
      {
          <error descr="'tstore' is only available for Cancun-compatible VMs (the EVM version is paris)">tstore</error>(0, 1)
      }
    """.trimIndent())
    myFixture.enableInspections(YulEvmVersionInspection::class.java)
    myFixture.checkHighlighting(false, false, false)
  }
}
