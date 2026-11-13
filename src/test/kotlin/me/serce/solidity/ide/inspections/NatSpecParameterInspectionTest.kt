package me.serce.solidity.ide.inspections

class NatSpecParameterInspectionTest : SolInspectionsTestBase(NatSpecParameterInspection()) {
  fun testMatchingTripleSlashTags() = checkByText(
    """
        contract A {
            /// @notice Adds
            /// @param a the first
            /// @param b the second
            /// @return the sum
            function add(uint a, uint b) public pure returns (uint) { return a + b; }
        }
    """.trimIndent()
  )

  fun testUnknownParamInTripleSlash() = checkByText(
    """
        contract A {
            /// @param a the first
            /// <warning descr="No parameter named 'c'">@param</warning> c the second
            function add(uint a, uint b) public pure returns (uint) { return a + b; }
        }
    """.trimIndent()
  )

  fun testUnknownParamInBlockComment() = checkByText(
    """
        contract A {
            /**
             * @param a the first
             * <warning descr="No parameter named 'x'">@param</warning> x the second
             * @return s the sum
             */
            function add(uint a, uint b) public pure returns (uint s) { return a + b; }
        }
    """.trimIndent()
  )

  fun testTooManyReturns() = checkByText(
    """
        contract A {
            /// @return first
            /// <warning descr="More '@return' tags than return values (1)">@return</warning> second
            function f() public pure returns (uint) { return 1; }

            /// @return a first
            /// @return b second
            function g() public pure returns (uint a, uint b) { return (1, 2); }

            /// <warning descr="'@return' documents a return value, but there is none">@return</warning> nothing
            function h() public pure {}
        }
    """.trimIndent()
  )

  fun testEventErrorModifier() = checkByText(
    """
        contract A {
            /// @param from sender
            /// <warning descr="No parameter named 'too'">@param</warning> too receiver
            event Transfer(address indexed from, address indexed to);

            /// @param needed amount
            /// <warning descr="No parameter named 'have'">@param</warning> have balance
            error Insufficient(uint needed, uint available);

            /// @param who caller
            /// <warning descr="No parameter named 'what'">@param</warning> what thing
            modifier only(address who) { _; }
        }
    """.trimIndent()
  )

  fun testIgnoresRegularCommentsAndDistantDocs() = checkByText(
    """
        contract A {
            // @param nope a regular comment is not NatSpec
            /* @param nope neither is a regular block comment */
            function f(uint a) public pure {}

            /// @param a documented
            uint x;
            function g(uint b) public pure {}
        }
    """.trimIndent()
  )

  fun testFreeFunction() = checkByText(
    """
        /// <warning descr="No parameter named 'y'">@param</warning> y the value
        function free(uint x) pure returns (uint) { return x; }
    """.trimIndent()
  )
}
