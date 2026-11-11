package me.serce.solidity.chain.debug

import junit.framework.TestCase

class RevertTracerTest : TestCase() {
  fun testSourceMapEntriesInheritOmittedFields() {
    val entries = SourceMapEntry.parse("10:5:0:-;;:3;20::1:i;:::o")
    assertEquals(
      listOf(SourceMapEntry(10, 5, 0), SourceMapEntry(10, 5, 0), SourceMapEntry(10, 3, 0), SourceMapEntry(20, 3, 1), SourceMapEntry(20, 3, 1)),
      entries
    )
  }

  fun testInstructionIndexSkipsPushData() {
    // PUSH1 0x80, PUSH0, PUSH2 0x0102, STOP
    val code = byteArrayOf(0x60, 0x80.toByte(), 0x5f, 0x61, 0x01, 0x02, 0x00)
    assertEquals(mapOf(0 to 0, 2 to 1, 3 to 2, 6 to 3), CompiledCode.instructionIndex(code))
  }

  fun testFailurePathFollowsFailedCalls() {
    val callee = "0x00000000000000000000000000000000000000aa"
    val steps = listOf(
      Step(1, 0, "PUSH1", emptyList()),
      Step(1, 2, "CALL", listOf("0xffff", callee)),
      Step(2, 0, "PUSH0", emptyList()),
      Step(2, 1, "REVERT", listOf("0x0", "0x0")),
      Step(1, 3, "PUSH0", listOf("0x0")),
      Step(1, 4, "REVERT", emptyList()),
    )
    val root = FrameAnalysis.build(steps, "0x00000000000000000000000000000000000000bb")
    val path = root.failurePath()
    assertEquals(2, path.size)
    assertEquals(listOf(0, 1), path[1].pcs)
    assertEquals("0x00000000000000000000000000000000000000AA", path[1].address)
  }

  fun testSuccessfulCallIsNotOnFailurePath() {
    val steps = listOf(
      Step(1, 0, "CALL", listOf("0xffff", "0xaa")),
      Step(2, 0, "RETURN", emptyList()),
      Step(1, 1, "REVERT", emptyList()),
    )
    val root = FrameAnalysis.build(steps, "0xbb")
    assertEquals(listOf(root), root.failurePath())
  }

  fun testEmptyCodeCheckIsRemembered() {
    val steps = listOf(
      Step(1, 0, "EXTCODESIZE", listOf("0x4b20993bc481177ec7e8f571cecae8a9e22c02db")),
      Step(1, 1, "ISZERO", listOf("0x0")),
      Step(1, 2, "REVERT", emptyList()),
    )
    val root = FrameAnalysis.build(steps, "0xbb")
    assertEquals(setOf("0x4B20993Bc481177ec7E8f571ceCaE8A9e22C02db"), root.checkedWithoutCode)
  }

  fun testPrecompiles() {
    assertTrue(RevertTracer.isPrecompile("0x0000000000000000000000000000000000000001"))
    assertTrue(RevertTracer.isPrecompile("0x0000000000000000000000000000000000000100"))
    assertFalse(RevertTracer.isPrecompile("0x4B20993Bc481177ec7E8f571ceCaE8A9e22C02db"))
  }

  fun testSnippetIsOneShortLine() {
    assertEquals("foo(a, …", CompiledCode.snippet("x = foo(a,\n    b);", 4, 17))
    assertEquals("foo(a, b)", CompiledCode.snippet("x = foo(a,   b);", 4, 15))
    assertEquals("if (x) { …", CompiledCode.snippet("if (x) {\n  y();\n}", 0, 17))
  }
}
