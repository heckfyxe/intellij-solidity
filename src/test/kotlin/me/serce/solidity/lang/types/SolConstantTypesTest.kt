package me.serce.solidity.lang.types

import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolExpression
import me.serce.solidity.lang.psi.SolStatement
import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

/** Rational constants, number units, common types of constants and typed operands, `abi.decode` arrays, `type(T)`. */
class SolConstantTypesTest : SolTestBase() {

  private fun checkTypes(@Language("Solidity") code: String, vararg expected: Pair<String, String>) {
    val file = InlineFile(code).psiFile
    val actual = PsiTreeUtil.findChildrenOfType(file, SolStatement::class.java)
      .mapNotNull { PsiTreeUtil.getChildOfType(it, SolExpression::class.java) }
      .associate { it.text to deInternalise(it.type.displayName()) }
    assertEquals(expected.toMap(), actual)
  }

  fun testRationalConstants() = checkTypes("""
      contract C {
        function f() public {
          1 / 2;
          2.5;
          .5;
          1e-3;
          2.5 * 2;
          1e18;
          1.5e3;
          0.5 ether;
          1.5 hours;
          2 gwei;
          7 / 2 * 2;
          -(1 / 2);
          (1 / 3) + (2 / 3);
          2 ** -1;
          0.1 + 0.2;
          1 / 0;
        }
      }
    """,
    "1 / 2" to "rational_const 1 / 2",
    "2.5" to "rational_const 5 / 2",
    ".5" to "rational_const 1 / 2",
    "1e-3" to "rational_const 1 / 1000",
    "2.5 * 2" to "int_const 5",
    "1e18" to "int_const 1000000000000000000",
    "1.5e3" to "int_const 1500",
    "0.5 ether" to "int_const 500000000000000000",
    "1.5 hours" to "int_const 5400",
    "2 gwei" to "int_const 2000000000",
    "7 / 2 * 2" to "int_const 7",
    "-(1 / 2)" to "rational_const -1 / 2",
    "(1 / 3) + (2 / 3)" to "int_const 1",
    "2 ** -1" to "rational_const 1 / 2",
    "0.1 + 0.2" to "rational_const 3 / 10",
    "1 / 0" to "<unknown>",
  )

  fun testLongConstantsAbbreviated() = checkTypes("""
      contract C {
        function f() public {
          2**256;
          -1;
        }
      }
    """,
    "2**256" to "int_const 1157...(70 digits omitted)...9936",
    "-1" to "int_const -1",
  )

  fun testCommonTypeWithConstant() = checkTypes("""
      contract C {
        function f(uint8 u, int8 i, uint16 u16) public {
          u + 300;
          u + 255;
          300 * u;
          i + 200;
          u + -1;
          u * 0.5;
          u + u16;
          u16 - i;
        }
      }
    """,
    "u + 300" to "uint16",
    "u + 255" to "uint8",
    "300 * u" to "uint16",
    "i + 200" to "<unknown>",
    "u + -1" to "<unknown>",
    "u * 0.5" to "<unknown>",
    "u + u16" to "uint16",
    "u16 - i" to "<unknown>",
  )

  fun testAbiDecodeArrays() = checkTypes("""
      contract C {
        struct S { uint a; }
        type Price is uint128;
        function h(bytes memory data) public {
          abi.decode(data, (uint[2], bytes32[]));
          abi.decode(data, (S[]));
          abi.decode(data, (address[][3]));
          abi.decode(data, (Price));
          abi.decode(data, (bytes, string));
        }
      }
    """,
    "abi.decode(data, (uint[2], bytes32[]))" to "(uint256[2],bytes32[])",
    "abi.decode(data, (S[]))" to "S[]",
    "abi.decode(data, (address[][3]))" to "address[][3]",
    "abi.decode(data, (Price))" to "ValueType(Price)",
    "abi.decode(data, (bytes, string))" to "(bytes,string)",
  )

  fun testDecodedArrayDeclaration() {
    InlineFile("""
      contract C {
        function h(bytes memory data) public {
          (uint[2] memory a, bytes32[] memory b) = abi.decode(data, (uint[2], bytes32[]));
          var (c, d) = abi.decode(data, (uint[2], bytes32[]));
          d;
        //^
        }
      }
    """)
    val (expr, _) = findElementAndDataInEditor<SolExpression>()
    assertEquals("bytes32[]", expr.type.toString())
  }

  fun testAbstractContractMetaType() = checkTypes("""
      abstract contract A {}
      library L {}
      contract C {
        function h() public {
          type(A).name;
          type(A).creationCode;
          type(A).runtimeCode;
          type(L).creationCode;
        }
      }
    """,
    "type(A).name" to "string",
    "type(A).creationCode" to "<unknown>",
    "type(A).runtimeCode" to "<unknown>",
    "type(L).creationCode" to "bytes",
  )

  fun testEnumMinMaxBefore088() = checkTypes("""
      pragma solidity >=0.8.0 <0.8.8;
      contract C {
        enum K { A, B }
        function h() public {
          type(K).max;
          type(uint8).max;
        }
      }
    """,
    "type(K).max" to "<unknown>",
    "type(uint8).max" to "uint8",
  )

  fun testEnumMinMaxWhenPragmaAllows088() = checkTypes("""
      pragma solidity ^0.8.0;
      contract C {
        enum K { A, B }
        function h() public {
          type(K).min;
        }
      }
    """,
    "type(K).min" to "K",
  )

  fun testRationalNotAssignableToIntegers() {
    val half = SolRationalConstant(Rational.of(1.toBigInteger(), 2.toBigInteger()))
    assertFalse(SolInteger.UINT_256.isAssignableFrom(half))
    assertFalse(SolFixedBytes(32).isAssignableFrom(half))
    assertEquals(SolInteger.ofConstant(3.toBigInteger()), SolRationalConstant.of(Rational.of(6.toBigInteger(), 2.toBigInteger())))
  }
}
