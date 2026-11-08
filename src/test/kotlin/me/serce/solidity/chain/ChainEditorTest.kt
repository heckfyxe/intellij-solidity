package me.serce.solidity.chain

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.util.SystemProperties
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.foundry.SolcLocator
import java.io.File

/**
 * Editor integration: the Deploy gutter icon and solc errors while editing. Uses real files, since forge and
 * solc read them from disk. Skipped when Foundry (or a solc for the pragma) is not installed.
 */
class ChainEditorTest : BasePlatformTestCase() {
  private val forge = File(SystemProperties.getUserHome(), ".foundry/bin/forge")

  override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

  private fun foundryProject() {
    myFixture.addFileToProject("foundry.toml", "[profile.default]\nsrc = \"src\"\nout = \"out\"\nlibs = []\n")
  }

  private fun deployGutters() = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { it.startsWith("Deploy ") }

  /** The first pass must not run forge under the highlighting read action (the platform logs that as an error, failing the test). */
  fun testDeployIconAppearsOnceConfigIsLoaded() {
    if (!forge.canExecute()) return
    foundryProject()
    val bank = myFixture.addFileToProject("src/Bank.sol", "pragma solidity ^0.8.20;\ncontract Bank { function f() public {} }\n")
    myFixture.configureFromExistingVirtualFile(bank.virtualFile)
    // config not known yet: loaded in the background, which then restarts highlighting (as it should in the IDE)
    (myFixture as CodeInsightTestFixtureImpl).canChangeDocumentDuringHighlighting(true)
    deployGutters()
    FoundryProject.find(project, bank.virtualFile)!!.config()
    assertEquals(listOf("Deploy Bank to a chain"), deployGutters())
  }

  fun testNoDeployIconOnTests() {
    if (!forge.canExecute()) return
    foundryProject()
    val test = myFixture.addFileToProject("test/Bank.t.sol", "pragma solidity ^0.8.20;\ncontract BankTest { function f() public {} }\n")
    FoundryProject.find(project, test.virtualFile)!!.config()
    myFixture.configureFromExistingVirtualFile(test.virtualFile)
    assertEmpty(deployGutters())
  }

  fun testSolcErrorsShowWhileEditing() {
    val text = "pragma solidity ^0.8.20;\ncontract A { function f() public pure { uint x = \"a\"; x; } }\n"
    if (!forge.canExecute() || SolcLocator.find(null, text) == null) return
    foundryProject()
    val file = myFixture.addFileToProject("src/A.sol", text)
    FoundryProject.find(project, file.virtualFile)!!.config() // so the gutter pass doesn't load it mid-highlighting
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
    assertTrue(errors.toString(), errors.any { it.contains("not implicitly convertible") })
  }
}
