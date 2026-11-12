package me.serce.solidity.ide.inspections

class ReservedKeywordInspectionTest : SolInspectionsTestBase(ReservedKeywordInspection()) {

  fun testReservedKeywordsAsIdentifiers() = checkByText("""
      pragma solidity ^0.8.0;
      contract A {
          uint /*@error descr="'after' is a reserved keyword and can't be used as an identifier"@*/after/*@/error@*/;
          struct /*@error descr="'typedef' is a reserved keyword and can't be used as an identifier"@*/typedef/*@/error@*/ { uint a; }
          function /*@error descr="'static' is a reserved keyword and can't be used as an identifier"@*/static/*@/error@*/(uint /*@error descr="'of' is a reserved keyword and can't be used as an identifier"@*/of/*@/error@*/) public {
              uint /*@error descr="'default' is a reserved keyword and can't be used as an identifier"@*/default/*@/error@*/ = /*@error descr="'of' is a reserved keyword and can't be used as an identifier"@*/of/*@/error@*/;
          }
      }
  """)

  fun testAssemblyIsIgnored() = checkByText("""
      pragma solidity ^0.8.0;
      contract A {
          function f() public pure returns (uint r) {
              assembly {
                  let x := 1
                  switch x
                  case 1 { r := 2 }
                  default { r := 3 }
              }
          }
      }
  """)

  fun testNotReportedWithoutPragma() = checkByText("""
      contract A {
          uint after;
      }
  """)

  fun testNotReportedForOldPragma() = checkByText("""
      pragma solidity ^0.4.24;
      contract A {
          uint alias;
      }
  """)

  fun testRegularIdentifiers() = checkByText("""
      pragma solidity ^0.8.20;
      contract A {
          uint value;
          uint global;
          uint layout;
          function inside(uint index) public {}
      }
  """)
}
