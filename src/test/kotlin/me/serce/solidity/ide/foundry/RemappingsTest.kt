package me.serce.solidity.ide.foundry

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RemappingsTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.addFileToProject("lib/forge-std/src/Test.sol", "")
    myFixture.addFileToProject("lib/openzeppelin-contracts/contracts/token/ERC20.sol", "")
    myFixture.addFileToProject("lib/openzeppelin-contracts/contracts/package.json", """{"name": "@openzeppelin/contracts"}""")
    myFixture.addFileToProject("lib/solady/README.md", "")
  }

  fun testSuggestsARemappingPerLibrary() {
    myFixture.configureByText(RemappingsFile.NAME, "forge-std/=lib/forge-std/src/\n<caret>")
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertSameElements(
      items,
      "@openzeppelin/contracts/=lib/openzeppelin-contracts/contracts/",
      "solady/=lib/solady/",
    )
  }

  fun testCompletesTargetDirectories() {
    myFixture.configureByText(RemappingsFile.NAME, "oz/=lib/openzeppelin-contracts/<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("oz/=lib/openzeppelin-contracts/contracts/<caret>")
  }

  fun testCompletesTopLevelDirectories() {
    myFixture.configureByText(RemappingsFile.NAME, "x/=li<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("x/=lib/<caret>")
  }

  fun testOtherTextFilesAreLeftAlone() {
    myFixture.configureByText("notes.txt", "forge<caret>")
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertFalse(items.any { "=" in it })
  }

  fun testInspection() {
    myFixture.enableInspections(RemappingsInspection::class.java)
    myFixture.configureByText(
      RemappingsFile.NAME,
      """
        forge-std/=lib/forge-std/src/
        src/:oz/=lib/openzeppelin-contracts/contracts
        missing/=<warning descr="Remapping target 'lib/missing/' does not exist">lib/missing/</warning>
        <error descr="A remapping has the form [context:]prefix=target">just-text</error>

      """.trimIndent()
    )
    myFixture.checkHighlighting()
  }
}
