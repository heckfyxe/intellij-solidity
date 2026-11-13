package me.serce.solidity.ide.inspections

class UsingForGlobalInspectionTest : SolInspectionsTestBase(UsingForGlobalInspection()) {

  fun testValidGlobalDirectives() = checkByText("""
      pragma solidity ^0.8.19;
      type Fixed is uint256;
      struct Point { uint x; uint y; }
      enum Kind { A, B }
      contract Token {}
      library L {
          function unwrapped(Fixed a) internal pure returns (uint) { return Fixed.unwrap(a); }
          function sum(Point memory p) internal pure returns (uint) { return p.x + p.y; }
          function first(Kind k) internal pure returns (bool) { return k == Kind.A; }
          function id(Token t) internal pure returns (Token) { return t; }
      }
      function add(Fixed a, Fixed b) pure returns (Fixed) { return Fixed.wrap(Fixed.unwrap(a) + Fixed.unwrap(b)); }
      using L for Fixed global;
      using {add as +} for Fixed global;
      using L for Point global;
      using L for Kind global;
      using L for Token global;
      using L for uint256;
      contract C {
          using L for *;
          using L for Point;
      }
  """)

  fun testGlobalInsideContract() = checkByText("""
      pragma solidity ^0.8.19;
      struct Point { uint x; }
      library L { function f(Point memory p) internal pure {} }
      contract C {
          using L for Point /*@error descr="\"global\" can only be used at file level"@*/global/*@/error@*/;
      }
  """)

  fun testGlobalWithElementaryType() = checkByText("""
      pragma solidity ^0.8.19;
      library L { function f(uint a) internal pure {} }
      using L for /*@error descr="Can only use \"global\" with user-defined types"@*/uint256/*@/error@*/ global;
      using L for /*@error descr="Can only use \"global\" with user-defined types"@*/uint256[]/*@/error@*/ global;
  """)

  fun testGlobalWithTypeInsideContract() = checkByText("""
      pragma solidity ^0.8.19;
      contract Holder { struct Inner { uint x; } }
      library L { function f(Holder.Inner memory p) internal pure {} }
      using L for /*@error descr="Can only use \"global\" with types defined in the same source unit at file level"@*/Holder.Inner/*@/error@*/ global;
  """)

  fun testGlobalWithUnresolvedTypeNotReported() = checkByText("""
      pragma solidity ^0.8.19;
      library L {}
      using L for Missing global;
  """)

  fun testWildcardAtFileLevel() = checkByText("""
      pragma solidity ^0.8.19;
      library L { function f(uint a) internal pure {} }
      using L for /*@error descr="The type has to be specified explicitly at file level (cannot use \"*\")"@*/*/*@/error@*/;
  """)
}
