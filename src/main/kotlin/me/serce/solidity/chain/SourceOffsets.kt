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

/** Zero-based line and column of offsets in one file's [text]. */
class LinePositions(private val text: String) {
  private val lineStarts: IntArray = run {
    val starts = ArrayList<Int>().apply { add(0) }
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    starts.toIntArray()
  }

  /** Of a solc byte offset. */
  fun lineCol(byteOffset: Int): Pair<Int, Int> = lineColAt(Offsets.byteToChar(text, byteOffset))

  /** Of a char offset. */
  fun lineColAt(offset: Int): Pair<Int, Int> {
    val line = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
    return line to (offset - lineStarts[line])
  }
}
