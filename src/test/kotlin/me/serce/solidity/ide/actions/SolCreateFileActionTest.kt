package me.serce.solidity.ide.actions

import com.intellij.ide.IdeView
import com.intellij.ide.actions.TestDialogBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDirectory
import com.intellij.psi.impl.file.PsiDirectoryFactory
import me.serce.solidity.utils.SolLightPlatformCodeInsightFixtureTestCase
import org.intellij.lang.annotations.Language

class SolCreateFileActionTest: SolLightPlatformCodeInsightFixtureTestCase() {
  fun testCreateContract() {
    val directoryFactory = PsiDirectoryFactory.getInstance(project)
    val dir = directoryFactory.createDirectory(myFixture.tempDirFixture.findOrCreateDir("foo"))
    val ctx = SimpleDataContext.builder()
      .add(LangDataKeys.IDE_VIEW, TestIdeView(dir))
      .add(CommonDataKeys.PROJECT, project)
      .add(TestDialogBuilder.TestAnswers.KEY, TestDialogBuilder.TestAnswers("myContract", SMART_CONTRACT_TEMPLATE))
      .build();

    val event = AnActionEvent.createFromDataContext("", null, ctx)
    ActionManager.getInstance().getAction("solidity.file.create")!!.actionPerformed(event)

    val file = dir.findFile("myContract.sol")!!
    @Language("Solidity")
    val content = """
      // SPDX-License-Identifier: UNLICENSED
      pragma solidity ^0.8.10;
      
      contract myContract {
          constructor() {}
      }
      
      """.trimIndent()
    assertEquals(content, file.text)
  }

  private fun create(name: String, template: String): PsiDirectory {
    val directoryFactory = PsiDirectoryFactory.getInstance(project)
    val dir = directoryFactory.createDirectory(myFixture.tempDirFixture.findOrCreateDir("foo"))
    val ctx = SimpleDataContext.builder()
      .add(LangDataKeys.IDE_VIEW, TestIdeView(dir))
      .add(CommonDataKeys.PROJECT, project)
      .add(TestDialogBuilder.TestAnswers.KEY, TestDialogBuilder.TestAnswers(name, template))
      .build()
    ActionManager.getInstance().getAction("solidity.file.create")!!.actionPerformed(AnActionEvent.createFromDataContext("", null, ctx))
    return dir
  }

  fun testCreateForgeTest() {
    val file = create("Counter", FORGE_TEST_TEMPLATE).findFile("Counter.t.sol")!!
    @Language("Solidity")
    val content = """
      // SPDX-License-Identifier: UNLICENSED
      pragma solidity ^0.8.13;

      import {Test} from "forge-std/Test.sol";

      contract CounterTest is Test {
          function setUp() public {}

          function test_Example() public {}
      }

      """.trimIndent()
    assertEquals(content, file.text)
  }

  fun testCreateForgeScript() {
    val file = create("DeployScript", FORGE_SCRIPT_TEMPLATE).findFile("Deploy.s.sol")!!
    assertTrue(file.text.contains("import {Script} from \"forge-std/Script.sol\";"))
    assertTrue(file.text.contains("contract DeployScript is Script {"))
    assertTrue(file.text.contains("vm.startBroadcast();"))
  }

  fun testForgeFileNames() {
    assertEquals("Counter.t" to "CounterTest", forgeFileNames("Counter", ".t", "Test"))
    assertEquals("Counter.t" to "CounterTest", forgeFileNames("CounterTest", ".t", "Test"))
    assertEquals("Counter.t" to "CounterTest", forgeFileNames("Counter.t.sol", ".t", "Test"))
    assertEquals("Test.t" to "TestTest", forgeFileNames("Test", ".t", "Test"))
    assertEquals("Deploy.s" to "DeployScript", forgeFileNames("Deploy.s", ".s", "Script"))
  }

  private class TestIdeView(private val dir: PsiDirectory) : IdeView {
    override fun getDirectories() = arrayOf(dir)
    override fun getOrChooseDirectory() = dir
  }
}
