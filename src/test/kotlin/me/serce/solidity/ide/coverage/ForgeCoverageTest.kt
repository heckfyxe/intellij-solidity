package me.serce.solidity.ide.coverage

import com.intellij.openapi.editor.Editor
import me.serce.solidity.utils.SolTestBase

class ForgeCoverageTest : SolTestBase() {
  // `forge coverage --report lcov` output of forge 1.9 for a Branchy contract tested only with a small input
  private val lcov = """
    TN:
    SF:src/Branchy.sol
    DA:5,1
    FN:5,Branchy.f
    FNDA:1,Branchy.f
    DA:6,1
    BRDA:6,0,0,0
    DA:7,0
    DA:9,1
    FNF:1
    FNH:1
    LF:4
    LH:3
    BRF:1
    BRH:0
    end_of_record
    TN:
    SF:src/Counter.sol
    DA:7,258
    FN:7,Counter.setNumber
    FNDA:258,Counter.setNumber
    DA:8,258
    end_of_record
  """.trimIndent()

  fun testParse() {
    val report = Lcov.parse(lcov)
    assertEquals(setOf("src/Branchy.sol", "src/Counter.sol"), report.keys)
    val branchy = report.getValue("src/Branchy.sol")
    assertEquals(mapOf(5 to 1L, 6 to 1L, 7 to 0L, 9 to 1L), branchy.lineHits)
    assertEquals(LineCoverage.COVERED, branchy.state(5))
    assertEquals(LineCoverage.PARTIAL, branchy.state(6))
    assertEquals(LineCoverage.UNCOVERED, branchy.state(7))
    assertNull(branchy.state(8))
    assertEquals(258L, report.getValue("src/Counter.sol").lineHits[8])
  }

  fun testBranchStates() {
    val report = Lcov.parse("SF:a.sol\nDA:1,3\nBRDA:1,0,0,2\nBRDA:1,0,1,1\nDA:2,0\nBRDA:2,1,0,-\nend_of_record\n").getValue("a.sol")
    assertEquals(LineCoverage.COVERED, report.state(1))
    assertEquals(LineCoverage.UNCOVERED, report.state(2))
  }

  fun testRecordsForTheSameFileAreMerged() {
    val report = Lcov.parse("SF:a.sol\nDA:1,1\nend_of_record\nSF:a.sol\nDA:1,2\nDA:2,0\nend_of_record\n").getValue("a.sol")
    assertEquals(mapOf(1 to 3L, 2 to 0L), report.lineHits)
  }

  fun testServiceMapsRelativePathsAndMarksEditors() {
    val file = myFixture.configureByText(
      "Branchy.sol",
      """
        // SPDX-License-Identifier: UNLICENSED
        pragma solidity ^0.8.13;

        contract Branchy {
            function f(uint256 x) public pure returns (uint256) {
                if (x > 10) {
                    return 1;
                }
                return 2;
            }
        }
      """.trimIndent()
    )
    val vFile = file.virtualFile
    val root = vFile.parent.path
    val report = Lcov.parse(lcov.replace("src/Branchy.sol", vFile.name))
    val service = ForgeCoverageService.getInstance(project)
    try {
      service.setReport(root, report)
      assertTrue(service.isActive)
      assertNotNull(service.coverageFor(vFile.path))
      assertEquals(4, markers(myFixture.editor).size)
      val lines = markers(myFixture.editor).map { myFixture.editor.document.getLineNumber(it.startOffset) + 1 }.sorted()
      assertEquals(listOf(5, 6, 7, 9), lines)

      // re-applying replaces the markers instead of piling them up
      service.apply(myFixture.editor)
      assertEquals(4, markers(myFixture.editor).size)
    } finally {
      service.clear()
    }
    assertFalse(service.isActive)
    assertEmpty(markers(myFixture.editor))
  }

  private fun markers(editor: Editor) =
    editor.markupModel.allHighlighters.filter { it.isValid && it.lineMarkerRenderer?.javaClass?.simpleName == "CoverageRenderer" }
}
