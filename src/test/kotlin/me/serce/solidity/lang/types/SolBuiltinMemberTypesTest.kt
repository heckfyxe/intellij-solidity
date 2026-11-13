package me.serce.solidity.lang.types

import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolExpression
import me.serce.solidity.lang.psi.SolStatement
import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

/** Types of constant expressions and builtin members (selectors, `type(T)` members, `concat`, `abi.decode`). */
class SolBuiltinMemberTypesTest : SolTestBase() {

  /** Checks the type of each expression statement in [code] against [expected] (expression text to type). */
  private fun checkTypes(@Language("Solidity") code: String, vararg expected: Pair<String, String>) {
    val file = InlineFile(code).psiFile
    val actual = PsiTreeUtil.findChildrenOfType(file, SolStatement::class.java)
      .mapNotNull { PsiTreeUtil.getChildOfType(it, SolExpression::class.java) }
      .associate { it.text to deInternalise(it.type.toString()) }
    assertEquals(expected.toMap(), actual)
  }

  fun testConstantExpressions() = checkTypes("""
      contract C {
        function f(int8 i, uint8 u) public {
          -1;
          -128;
          -129;
          -(1);
          1 - 2;
          ~0;
          2**256 - 1;
          1 << 255;
          10 / 5;
          i - 1;
          1 + u;
          2 ** u;
          u ** 2;
          !true;
        }
      }
    """,
    "-1" to "int8",
    "-128" to "int8",
    "-129" to "int16",
    "-(1)" to "int8",
    "1 - 2" to "int8",
    "~0" to "int8",
    "2**256 - 1" to "uint256",
    "1 << 255" to "uint256",
    "10 / 5" to "uint8",
    "i - 1" to "int8",
    "1 + u" to "uint8",
    "2 ** u" to "uint256",
    "u ** 2" to "uint8",
    "!true" to "bool",
  )

  fun testLiteralAssignability() {
    fun literal(value: Long) = SolInteger.ofConstant(value.toBigInteger())
    assertTrue(SolInteger(false, 256).isAssignableFrom(literal(-1)))
    assertTrue(SolInteger(false, 8).isAssignableFrom(literal(-128)))
    assertTrue(SolInteger(false, 8).isAssignableFrom(literal(127)))
    assertFalse(SolInteger(false, 8).isAssignableFrom(literal(128)))
    assertFalse(SolInteger(false, 8).isAssignableFrom(literal(-129)))
    assertTrue(SolInteger(true, 8).isAssignableFrom(literal(255)))
    assertFalse(SolInteger(true, 8).isAssignableFrom(literal(256)))
    assertFalse(SolInteger(true, 256).isAssignableFrom(literal(-1)))
    // variables keep the old rules
    assertFalse(SolInteger(false, 8).isAssignableFrom(SolInteger(true, 8)))
    assertTrue(SolInteger(false, 16).isAssignableFrom(SolInteger(true, 8)))
  }

  fun testSelectors() = checkTypes("""
      error E(uint a);
      event Ev(uint a);
      interface I {
        event IEv();
        error IE();
        function g() external;
      }
      contract C {
        error CE();
        event CEv(address);
        function f() public {}
        function h() public {
          E.selector;
          Ev.selector;
          CE.selector;
          CEv.selector;
          C.CE.selector;
          I.IEv.selector;
          I.IE.selector;
          this.f.selector;
          C.f.selector;
          I.g.selector;
          f.selector;
        }
      }
    """,
    "E.selector" to "bytes4",
    "Ev.selector" to "bytes32",
    "CE.selector" to "bytes4",
    "CEv.selector" to "bytes32",
    "C.CE.selector" to "bytes4",
    "I.IEv.selector" to "bytes32",
    "I.IE.selector" to "bytes4",
    "this.f.selector" to "bytes4",
    "C.f.selector" to "bytes4",
    "I.g.selector" to "bytes4",
    "f.selector" to "bytes4",
  )

  fun testMetaTypeMembers() = checkTypes("""
      interface I { function g() external; }
      contract C {
        enum K { A, B }
        function h() public {
          type(uint8).max;
          type(int).min;
          type(int16).max;
          type(uint256).max - 1;
          type(K).max;
          type(C).name;
          type(C).creationCode;
          type(C).runtimeCode;
          type(I).interfaceId;
          type(I).name;
        }
      }
    """,
    "type(uint8).max" to "uint8",
    "type(int).min" to "int256",
    "type(int16).max" to "int16",
    "type(uint256).max - 1" to "uint256",
    "type(K).max" to "K",
    "type(C).name" to "string",
    "type(C).creationCode" to "bytes",
    "type(C).runtimeCode" to "bytes",
    "type(I).interfaceId" to "bytes4",
    "type(I).name" to "string",
  )

  fun testBytesMembers() = checkTypes("""
      contract C {
        function h(bytes memory data, string memory s, address x, bytes4 b4, bytes32 b32) public {
          bytes.concat(data, b32, "x");
          bytes.concat();
          string.concat(s, s);
          b4.length;
          b4[0];
          x.code.length;
          bytes(s).length;
        }
      }
    """,
    "bytes.concat(data, b32, \"x\")" to "bytes",
    "bytes.concat()" to "bytes",
    "string.concat(s, s)" to "string",
    "b4.length" to "uint8",
    "b4[0]" to "bytes1",
    "x.code.length" to "uint256",
    "bytes(s).length" to "uint256",
  )

  fun testAbiDecode() = checkTypes("""
      contract C {
        struct S { uint a; }
        function h(bytes memory data) public {
          abi.decode(data, (uint, address));
          abi.decode(data, (uint));
          abi.decode(data, (S));
        }
      }
    """,
    "abi.decode(data, (uint, address))" to "(uint256,address)",
    "abi.decode(data, (uint))" to "uint256",
    "abi.decode(data, (S))" to "S",
  )

  fun testAbiDecodeDeclaration() {
    InlineFile("""
      contract C {
        function h(bytes memory data) public {
          (uint a, address b) = abi.decode(data, (uint, address));
          var (c, d) = abi.decode(data, (bool, bytes32));
          d;
        //^
        }
      }
    """)
    val (expr, _) = findElementAndDataInEditor<SolExpression>()
    assertEquals("bytes32", expr.type.toString())
  }
}
