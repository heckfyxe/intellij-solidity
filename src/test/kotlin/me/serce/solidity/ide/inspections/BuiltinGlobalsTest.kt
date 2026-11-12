package me.serce.solidity.ide.inspections

class BuiltinGlobalsTest : SolInspectionsTestBase(ResolveNameInspection()) {
  fun testBlobhashIsGlobal() = checkByText("""
        contract a {
            function f() public view returns (bytes32) {
                return blobhash(0);
            }
        }
    """)

  fun testRemovedSha3IsUndefined() = checkByText("""
        contract a {
            function f() public pure returns (bytes32) {
                return /*@warning descr="'sha3' is undefined"@*/sha3/*@/warning@*/("");
            }
        }
    """)
}
