package me.serce.solidity.chain.foundry

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class ShadowProjectTest : TestCase() {
  private lateinit var root: Path

  override fun setUp() {
    super.setUp()
    root = Files.createTempDirectory("shadow-test-root")
    Files.writeString(root.resolve("foundry.toml"), "[profile.default]\n")
    Files.createDirectories(root.resolve("src/sub"))
    Files.writeString(root.resolve("src/A.sol"), "saved A")
    Files.writeString(root.resolve("src/B.sol"), "saved B")
    Files.writeString(root.resolve("src/sub/C.sol"), "saved C")
    Files.createDirectories(root.resolve("lib/dep"))
    Files.writeString(root.resolve("lib/dep/D.sol"), "dep")
    Files.createDirectories(root.resolve("cache"))
    Files.writeString(root.resolve("cache/state.json"), "{}")
  }

  override fun tearDown() {
    root.toFile().deleteRecursively()
    super.tearDown()
  }

  fun testUnsavedFilesAreRealAndTheRestAreLinks() {
    var seen: Path? = null
    val result = ShadowProject.run(root, mapOf("src/A.sol" to "unsaved A", "src/sub/New.sol" to "new"), setOf("cache")) { shadow ->
      seen = shadow
      assertEquals("unsaved A", Files.readString(shadow.resolve("src/A.sol")))
      assertFalse(Files.isSymbolicLink(shadow.resolve("src/A.sol")))
      assertFalse(Files.isSymbolicLink(shadow.resolve("src")))
      assertTrue(Files.isSymbolicLink(shadow.resolve("src/B.sol")))
      assertEquals("saved B", Files.readString(shadow.resolve("src/B.sol")))
      assertFalse(Files.isSymbolicLink(shadow.resolve("src/sub")))
      assertTrue(Files.isSymbolicLink(shadow.resolve("src/sub/C.sol")))
      assertEquals("new", Files.readString(shadow.resolve("src/sub/New.sol")))
      assertTrue(Files.isSymbolicLink(shadow.resolve("lib")))
      assertTrue(Files.isSymbolicLink(shadow.resolve("foundry.toml")))
      assertFalse(Files.exists(shadow.resolve("cache")))
      "done"
    }
    assertEquals("done", result)
    assertFalse("shadow is deleted", Files.exists(seen!!))
    // deleting the shadow removed links, never what they point to
    assertEquals("saved A", Files.readString(root.resolve("src/A.sol")))
    assertEquals("saved B", Files.readString(root.resolve("src/B.sol")))
    assertEquals("saved C", Files.readString(root.resolve("src/sub/C.sol")))
    assertEquals("dep", Files.readString(root.resolve("lib/dep/D.sol")))
    assertEquals("{}", Files.readString(root.resolve("cache/state.json")))
    assertFalse(Files.exists(root.resolve("src/sub/New.sol")))
  }
}
