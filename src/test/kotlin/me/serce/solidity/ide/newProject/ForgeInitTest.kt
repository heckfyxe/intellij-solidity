package me.serce.solidity.ide.newProject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.SystemProperties
import junit.framework.TestCase
import java.io.File

class ForgeInitTest : TestCase() {
  fun testCommand() {
    assertEquals(listOf("forge", "init", "--force", "."), ForgeInitOptions(git = true, empty = false, template = "").command("forge"))
    assertEquals(
      listOf("forge", "init", "--force", "--no-git", "--empty", "--template", "foundry-rs/forge-template", "."),
      ForgeInitOptions(git = false, empty = true, template = " foundry-rs/forge-template ").command("forge"),
    )
  }

  /** The IDE has created `.idea` before forge runs, which plain `forge init` refuses. */
  fun testInitsDirectoryTheIdeAlreadyUses() {
    val forge = File(SystemProperties.getUserHome(), ".foundry/bin/forge")
    if (!forge.canExecute()) return
    val dir = FileUtil.createTempDirectory("forge-init", null)
    File(dir, ".idea").mkdirs()
    File(dir, ".idea/misc.xml").writeText("<project/>")
    // --no-git: no network needed to clone forge-std
    val command = GeneralCommandLine(ForgeInitOptions(git = false, empty = false, template = "").command(forge.path)).withWorkDirectory(dir)
    val output = CapturingProcessHandler(command).runProcess(120_000)
    assertEquals(output.stderr + output.stdout, 0, output.exitCode)
    assertTrue(File(dir, "foundry.toml").isFile)
    assertTrue(File(dir, "src/Counter.sol").isFile)
    assertTrue(File(dir, ".idea/misc.xml").isFile)

    ensureGitignore(dir.toPath()) // --no-git leaves no .gitignore
    val ignored = File(dir, ".gitignore").readLines()
    assertTrue(ignored.containsAll(listOf("cache/", "out/", ".env", ".idea/")))
  }

  fun testGitignoreKeepsForgesAndAddsIdeFolder() {
    val dir = FileUtil.createTempDirectory("gitignore", null)
    val file = File(dir, ".gitignore").apply { writeText("out/\n") }
    ensureGitignore(dir.toPath())
    assertEquals("out/\n\n# JetBrains IDE\n.idea/\n", file.readText())
    ensureGitignore(dir.toPath())
    assertEquals("idempotent", "out/\n\n# JetBrains IDE\n.idea/\n", file.readText())

    file.writeText("/.idea\n")
    ensureGitignore(dir.toPath())
    assertEquals("already ignored", "/.idea\n", file.readText())
  }
}
