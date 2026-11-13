package me.serce.solidity.ide.inspections

class ConstantConversionInspectionTest : SolInspectionsTestBase(ConstantConversionInspection()) {

  fun testFittingConstants() = checkByText("""
      uint constant WAD = 1e18;
      contract C {
          uint8 public constant DECIMALS = 18;
          uint256 public constant MAX = type(uint256).max;
          uint256 constant HALF = 0.5 ether;
          int8 constant MIN = -128;
          bytes32 constant ZERO = 0;
          uint256 x = 2**256 - 1;
          mapping(address => uint8) m;

          function f(uint8 a) public {
              uint8 b = 255;
              uint c = 2.5 * 2;
              uint d = 1.5 hours;
              int e = -1;
              uint8 g = a + 1;
              uint h = a;
              x = 7 / 2 * 2;
              m[msg.sender] = 200;
              b += 1;
              bytes2 hx = hex"0102";
              uint8 k = c > 0 ? 1 : 2;
          }
      }
  """)

  fun testConstantsOutOfRange() = checkByText("""
      uint8 constant BAD = /*@error descr="Type int_const 256 is not implicitly convertible to expected type uint8"@*/256/*@/error@*/;
      contract C {
          uint8 x = /*@error descr="Type int_const 300 is not implicitly convertible to expected type uint8"@*/300/*@/error@*/;
          mapping(address => uint8) m;

          function f() public {
              int8 a = /*@error descr="Type int_const -129 is not implicitly convertible to expected type int8"@*/-129/*@/error@*/;
              uint b = /*@error descr="Type int_const -1 is not implicitly convertible to expected type uint256"@*/-1/*@/error@*/;
              uint64 c = /*@error descr="Type int_const 1000000000000000000000 is not implicitly convertible to expected type uint64"@*/1000 ether/*@/error@*/;
              m[msg.sender] = /*@error descr="Type int_const 256 is not implicitly convertible to expected type uint8"@*/256/*@/error@*/;
              x = /*@error descr="Type int_const 1000 is not implicitly convertible to expected type uint8"@*/1000/*@/error@*/;
          }
      }
  """)

  fun testFractionalConstants() = checkByText("""
      contract C {
          uint y;
          function f() public {
              uint a = /*@error descr="Type rational_const 1 / 2 is not implicitly convertible to expected type uint256"@*/1 / 2/*@/error@*/;
              int b = /*@error descr="Type rational_const -5 / 2 is not implicitly convertible to expected type int256"@*/-2.5/*@/error@*/;
              y = /*@error descr="Type rational_const 1 / 1000 is not implicitly convertible to expected type uint256"@*/1e-3/*@/error@*/;
          }
      }
  """)

  fun testModuloOfFractions() = checkByText("""
      contract C {
          function f() public {
              uint a = /*@error descr="Type rational_const 3 / 2 is not implicitly convertible to expected type uint256"@*/5.5 % 2/*@/error@*/;
              uint b = 7 % 2;
              int c = -7 % 2;
          }
      }
  """)

  fun testUnknownTypesNotReported() = checkByText("""
      contract C {
          function f(Unknown u) public {
              Unknown a = 1 / 2;
              u.field = 1 / 2;
              var b = 1 / 2;
              fixed c = 1.5;
          }
      }
  """)
}
