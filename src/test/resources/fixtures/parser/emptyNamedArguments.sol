contract C {
    function g() internal pure {}

    function f() public pure {
        g({});
    }
}
