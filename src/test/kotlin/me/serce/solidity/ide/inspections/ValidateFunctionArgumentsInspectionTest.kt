package me.serce.solidity.ide.inspections

class ValidateFunctionArgumentsInspectionTest : SolInspectionsTestBase(ValidateFunctionArgumentsInspection()) {
  fun testTypeAssertFailure() = checkByText("""
        contract a {
            function a() {
                assert(/*@error descr="Argument of type 'string' is not assignable to parameter of type 'bool'"@*/"myString"/*@/error@*/);
            }
        }
    """)

  fun testTypeAssertSuccess() = checkByText("""
        contract a {
            function a() {
                assert(true);
            }
        }
    """)

  fun testWrongNumberOfArguments() = checkByText("""
        contract a {
            function a() {
                b(/*@error descr="Expected 1 argument, but got 2"@*/1, 2/*@/error@*/);
            }
            
            function b(int a) {
            
            }
        }
    """)

  fun testValidationDisabled() = checkByText("""
        contract a {
            function a() {
                b(1, 2);
            }
            
            /**
            * @custom:no_validation
            */
            function b(int a) {
            
            }
        }
    """)

  fun testVarargs() = checkByText("""
        contract a {
            function a() {
                string r = string.concat('a', 'b', 'c');
                string r2 = string.concat(/*@error descr="Argument of type 'uint8' is not assignable to parameter of type 'string'"@*/'a', 2, 'c'/*@/error@*/); 
            }
            
            function b(int a) {

            }
        }
    """)

  fun testNumberConstantsFitSignedParameters() = checkByText("""
        contract a {
            function a() {
                i(-1);
                i(-5 + 2);
                i8(-128);
                i8(127);
                i8(1);
                i8(-(1));
                u8(255);
                u8(2**8 - 1);
                i(type(int).min);
                u8(type(uint8).max);
                u(type(uint256).max - 1);
            }

            function i(int x) {}
            function i8(int8 x) {}
            function u8(uint8 x) {}
            function u(uint x) {}
        }
    """)

  fun testNumberConstantsOutOfRange() = checkByText("""
        contract a {
            function a() {
                i8(/*@error descr="Argument of type 'int16' is not assignable to parameter of type 'int8'"@*/-129/*@/error@*/);
                u(/*@error descr="Argument of type 'int8' is not assignable to parameter of type 'uint256'"@*/-1/*@/error@*/);
                u8(/*@error descr="Argument of type 'uint16' is not assignable to parameter of type 'uint8'"@*/256/*@/error@*/);
                u8(/*@error descr="Argument of type 'uint16' is not assignable to parameter of type 'uint8'"@*/type(uint16).max/*@/error@*/);
            }

            function i8(int8 x) {}
            function u8(uint8 x) {}
            function u(uint x) {}
        }
    """)

  fun testBuiltinMembers() = checkByText("""
        interface I { event Transfer(address indexed from, address indexed to, uint value); }
        contract C {
            error Unauthorized(address who);
            function run(bytes memory data, bytes32 salt, string memory s) public {
                b4(Unauthorized.selector);
                b32(I.Transfer.selector);
                b4(type(I).interfaceId);
                b4(this.run.selector);
                bs(bytes.concat(data, salt, "x"));
                st(string.concat(s, type(C).name));
                u(abi.decode(data, (uint)));
                u8(salt.length);
            }

            function b4(bytes4 x) {}
            function b32(bytes32 x) {}
            function bs(bytes memory x) {}
            function st(string memory x) {}
            function u(uint x) {}
            function u8(uint8 x) {}
        }
    """)

  fun testSelectorTypeMismatch() = checkByText("""
        contract a {
            event Ev();
            function a() {
                b4(/*@error descr="Argument of type 'bytes32' is not assignable to parameter of type 'bytes4'"@*/Ev.selector/*@/error@*/);
            }

            function b4(bytes4 x) {}
        }
    """)
}
