contract C {
    function g() internal pure returns (uint, uint) {
        return (1, 2);
    }

    function f(uint a, uint b) public pure {
        uint x = (a + b);
        (uint c, uint d) = (1, 2);
        (uint e, uint h) = g();
        (, uint i) = g();
        (a, b) = (b, a);
        (a, b) = g();
        (a, ) = g();
    }
}
