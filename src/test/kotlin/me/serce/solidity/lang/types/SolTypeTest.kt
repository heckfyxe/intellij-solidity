package me.serce.solidity.lang.types

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolTypeTest {
  @Test
  fun testUintIntCanBeApplied() {
    val uint256 = SolInteger(true, 256)
    val uint248 = SolInteger(true, 248)
    val int256 = SolInteger(false, 256)

    assertFalse(uint256.isAssignableFrom(int256))
    assertFalse(int256.isAssignableFrom(uint256))
    assertTrue(int256.isAssignableFrom(uint248))
  }

  @Test
  fun testString() {
    assertTrue(SolBytes.isAssignableFrom(SolString(0)))
  }

  @Test
  fun testOnlyStringLiteralsConvertToFixedBytes() {
    assertTrue(SolFixedBytes(3).isAssignableFrom(SolString(3)))
    // a `string` value, whose type name has 6 letters, isn't a 6-byte literal
    assertFalse(SolFixedBytes(6).isAssignableFrom(SolString()))
  }

  @Test
  fun testBytesElementIsBytes1() {
    assertTrue(SolBytes.type == SolFixedBytes(1))
  }

}
