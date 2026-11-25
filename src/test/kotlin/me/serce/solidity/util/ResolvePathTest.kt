package me.serce.solidity.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.serce.solidity.chain.foundry.FoundryProject

class ResolvePathTest : BasePlatformTestCase() {
  fun testRelativeAndAbsolutePaths() {
    val file = myFixture.tempDirFixture.createFile("src/A.sol", "")
    val root = file.parent.parent
    assertEquals(file, root.resolvePath("src/A.sol"))
    assertEquals(file, root.resolvePath("src\\A.sol"))
    assertEquals(file, root.resolvePath(file.path))
    assertNull(root.resolvePath("src/Missing.sol"))
    assertNull((null as com.intellij.openapi.vfs.VirtualFile?).resolvePath("src/A.sol"))
  }

  fun testFoundryRootAcceptsRemappingsOnlyWhenAsked() {
    val file = myFixture.tempDirFixture.createFile("p/src/A.sol", "")
    myFixture.tempDirFixture.createFile("p/remappings.txt", "")
    assertNull(FoundryProject.rootOf(file))
    assertEquals(file.parent.parent, FoundryProject.rootOf(file, orRemappings = true))
  }

  fun testLibraryDirs() {
    assertEquals(listOf("lib", "dependencies"), FoundryProject.libraryDirs(null))
  }
}
