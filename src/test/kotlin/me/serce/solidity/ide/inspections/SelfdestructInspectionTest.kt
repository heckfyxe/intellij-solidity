package me.serce.solidity.ide.inspections

class SelfdestructInspectionTest : SolInspectionsTestBase(SelfdestructRenameInspection()) {
  fun test() = checkByText("""
        contract a {
            function a() {
                /*@warning descr="Suicide is deprecated. rename to selfdestruct. EIP 6"@*/suicide(owner)/*@/warning@*/;
            }
        }
    """)

  fun testLeftToRemovedFeatureInspectionFor050() = checkByText("""
        pragma solidity ^0.5.0;
        contract a {
            function f(address payable owner) public {
                suicide(owner);
            }
        }
    """)
}
