package me.serce.solidity.lang.core

import junit.framework.TestCase

class SolidityVersionRangeTest : TestCase() {
  private fun range(text: String) = SolidityVersionRange.parse(text) ?: error("Failed to parse `$text`")
  private fun v(text: String) = SolidityVersion.of(text)

  private fun assertRange(text: String, min: String, maxExclusive: String?) {
    val r = range(text)
    assertEquals("min of `$text`", v(min), r.min)
    assertEquals("max of `$text`", maxExclusive?.let { v(it) }, r.maxExclusive)
  }

  fun testExact() {
    assertRange("0.8.24", "0.8.24", "0.8.25")
    assertRange("=0.8.24", "0.8.24", "0.8.25")
    assertRange("v0.8.24", "0.8.24", "0.8.25")
  }

  fun testCaret() {
    assertRange("^0.8.20", "0.8.20", "0.9.0")
    assertRange("^0.4.24", "0.4.24", "0.5.0")
    assertRange("^1.2.3", "1.2.3", "2.0.0")
    assertRange("^0.0.3", "0.0.3", "0.0.4")
    assertRange("^0.8", "0.8.0", "0.9.0")
    assertRange("^0", "0.0.0", "1.0.0")
  }

  fun testTilde() {
    assertRange("~0.7.0", "0.7.0", "0.8.0")
    assertRange("~0.7.6", "0.7.6", "0.8.0")
    assertRange("~0.7", "0.7.0", "0.8.0")
    assertRange("~1", "1.0.0", "2.0.0")
  }

  fun testComparators() {
    assertRange(">=0.8.0 <0.9.0", "0.8.0", "0.9.0")
    assertRange(">= 0.8.0 < 0.9.0", "0.8.0", "0.9.0")
    assertRange(">=0.6.2 <=0.8.4", "0.6.2", "0.8.5")
    assertRange(">0.8.3", "0.8.4", null)
    assertRange(">0.8", "0.9.0", null)
    assertRange(">=0.8", "0.8.0", null)
    assertRange("<0.9", "0.0.0", "0.9.0")
    assertRange("<=0.8", "0.0.0", "0.9.0")
    assertRange(">=0.4.22", "0.4.22", null)
  }

  fun testWildcards() {
    assertRange("0.8.x", "0.8.0", "0.9.0")
    assertRange("0.8.*", "0.8.0", "0.9.0")
    assertRange("0.8", "0.8.0", "0.9.0")
    assertRange("*", "0.0.0", null)
  }

  fun testHyphen() {
    assertRange("0.8.0 - 0.8.20", "0.8.0", "0.8.21")
    assertRange("0.7 - 0.8", "0.7.0", "0.9.0")
  }

  fun testAlternatives() {
    assertRange("^0.7.0 || ^0.8.0", "0.7.0", "0.9.0")
    assertRange("0.4.24 || >=0.8.0", "0.4.24", null)
    assertRange(">=0.8.0 <0.8.5 || 0.8.20", "0.8.0", "0.8.21")
  }

  fun testPrereleaseIsIgnored() {
    assertRange("^0.8.0-beta.1", "0.8.0", "0.9.0")
  }

  fun testInvalid() {
    assertNull(SolidityVersionRange.parse("foo"))
    assertNull(SolidityVersionRange.parse("^0.8.0abc"))
    assertNull(SolidityVersionRange.parse(">>0.8.0"))
  }

  fun testPredicates() {
    val r = range("^0.8.20")
    assertTrue(r.isAtLeast("0.8.0"))
    assertTrue(r.isAtLeast("0.8.20"))
    assertFalse(r.isAtLeast("0.8.21"))
    assertTrue(r.canBeAtLeast("0.8.31"))
    assertFalse(r.canBeAtLeast("0.9.0"))
    assertTrue(r.isBelow("0.9.0"))
    assertFalse(r.isBelow("0.8.25"))

    val open = range(">=0.4.22")
    assertFalse(open.isAtLeast("0.5.0"))
    assertTrue(open.canBeAtLeast("0.8.31"))
    assertFalse(open.isBelow("0.9.0"))

    val alternatives = range("^0.4.24 || ^0.8.0")
    assertFalse(alternatives.isAtLeast("0.5.0"))
  }

  fun testIntersectAndEmpty() {
    val r = range(">=0.8.0").intersect(range("<0.8.0"))
    assertTrue(r.isEmpty)
    assertFalse(r.isAtLeast("0.0.0"))
    assertEquals(range("^0.8.10"), range(">=0.8.10").intersect(range("^0.8.0")))
  }
}
