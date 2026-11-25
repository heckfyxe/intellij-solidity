package me.serce.solidity.chain

object Offsets {
  /** solc reports UTF-8 byte offsets; IntelliJ wants char offsets. */
  fun byteToChar(text: String, byteOffset: Int): Int {
    var bytes = 0
    var i = 0
    while (i < text.length && bytes < byteOffset) {
      val cp = text.codePointAt(i)
      bytes += when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
      }
      i += Character.charCount(cp)
    }
    return i
  }
}
