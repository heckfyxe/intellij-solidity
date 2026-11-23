contract C {
    uint szabo = 1;
    uint finney = szabo + 2;

    function f() public pure returns (uint) {
        return 1 szabo + 2 finney;
    }
}
