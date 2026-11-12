package me.serce.solidity.lang.core

import me.serce.solidity.utils.SolTestBase

class SolidityFileVersionTest : SolTestBase() {
  private fun versionOf(code: String) = InlineFile(code).psiFile.solidityVersionRange()

  fun testNoPragma() {
    assertNull(versionOf("contract A {}"))
  }

  fun testOtherPragmasAreIgnored() {
    assertNull(versionOf("pragma abicoder v2;\npragma experimental SMTChecker;\ncontract A {}"))
  }

  fun testSinglePragma() {
    val range = versionOf("// SPDX-License-Identifier: MIT\npragma solidity ^0.8.20;\ncontract A {}")!!
    assertEquals(SolidityVersionRange(SolidityVersion.of("0.8.20"), SolidityVersion.of("0.9.0")), range)
  }

  fun testRangePragma() {
    val range = versionOf("pragma solidity >=0.8.0 <0.9.0;\ncontract A {}")!!
    assertTrue(range.isAtLeast("0.8.0"))
    assertFalse(range.isAtLeast("0.8.1"))
  }

  fun testMultiplePragmasIntersect() {
    val range = versionOf("pragma solidity >=0.6.0;\npragma solidity <0.8.0;\npragma solidity ^0.7.2;\ncontract A {}")!!
    assertEquals(SolidityVersionRange(SolidityVersion.of("0.7.2"), SolidityVersion.of("0.8.0")), range)
  }

  fun testUnparsablePragmaIsIgnored() {
    assertNull(versionOf("pragma solidity foo;\ncontract A {}"))
  }

  fun testElementHelper() {
    val file = InlineFile("pragma solidity 0.8.24;\ncontract A {}").psiFile
    val contract = file.children.last { it is me.serce.solidity.lang.psi.SolContractDefinition }
    assertTrue(contract.isSolidityVersionAtLeast("0.8.24"))
    assertFalse(contract.isSolidityVersionAtLeast("0.8.25"))
  }
}
