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

            /// <warning descr="'@param' is not valid for state variables">@param</warning> a documented
            uint x;
            function g(uint b) public pure {}
        }
    """.trimIndent()
  )

  fun testConstructor() = checkByText(
    """
        contract A {
            /// @param owner_ the owner
            /// <warning descr="No parameter named 'admin'">@param</warning> admin nope
            /// <warning descr="'@return' documents a return value, but there is none">@return</warning> nothing
            constructor(address owner_) {}
        }
    """.trimIndent()
  )

  fun testStateVariables() = checkByText(
    """
        contract A {
            /// @notice The total supply
            /// @return the supply
            uint256 public totalSupply;

            /// @return the first
            /// <warning descr="A public state variable can have only one '@return' tag">@return</warning> the second
            mapping(address => uint256) public balanceOf;

            /// @dev internal counter
            /// <warning descr="'@return' is only valid for public state variables">@return</warning> the count
            uint256 internal count;

            /// <warning descr="'@param' is not valid for state variables">@param</warning> owner the owner
            mapping(address => uint256) public allowance;
        }
    """.trimIndent()
  )

  fun testDuplicateParam() = checkByText(
    """
        contract A {
            /// @param a the first
            /// @param b the second
            /// <warning descr="Parameter 'a' is documented more than once">@param</warning> a again
            function f(uint a, uint b) public pure {}

            /**
             * @param to receiver
             * <warning descr="Parameter 'to' is documented more than once">@param</warning> to receiver again
             */
            event Sent(address to);
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
