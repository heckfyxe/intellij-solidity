import me.serce.solidity.ide.inspections.SolInspectionsTestBase
import me.serce.solidity.ide.inspections.ValidateEmitEventArgumentsInspection

class ValidateEmitEventArgumentsInspectionTest : SolInspectionsTestBase(ValidateEmitEventArgumentsInspection()) {
  fun testRationalConstantArgument() = checkByText("""
        contract a {
            event Paid(uint amount);
            function f() public {
                emit Paid(0.5 ether);
                /*@error descr="Argument of type 'rational_const 1 / 2' is not assignable to parameter of type 'uint256'"@*/emit Paid(1 / 2)/*@/error@*/;
            }
        }
    """)

  fun testNoArguments() = checkByText("""
        contract a {
            event TestEvent(uint a);
            function a() returns (uint result) {
            /*@error descr="No arguments"@*/emit TestEvent()/*@/error@*/;
                return 1;
            }
        }
    """)

  fun testArgumentsLengthNotMatch() = checkByText("""
        contract a {
            event TestEvent(uint a, uint b);
            function a() returns (uint result) {
            /*@error descr="Expected 2 arguments, but got 1"@*/emit TestEvent(1)/*@/error@*/;
                return 1;
            }
        }
    """)

  fun testArgumentsTypeNotMatch() = checkByText("""
        contract a {
            event TestEvent(uint a, uint b);
            function a() returns (uint result) {
            /*@error descr="Argument of type 'string' is not assignable to parameter of type 'uint256'"@*/emit TestEvent(1, "abc")/*@/error@*/;
                return 1;
            }
        }
    """)

  fun testNoErrors() = checkByText("""
        contract a {
            event TestEvent(uint a, uint b);
            function a() returns (uint result) {
                emit TestEvent(1, 2);
                return 1;
            }
        }
    """)

  fun testEventWithoutParameters() = checkByText("""
        contract a {
            event Ping();
            function f() public {
                emit Ping();
            }
        }
    """)
}
