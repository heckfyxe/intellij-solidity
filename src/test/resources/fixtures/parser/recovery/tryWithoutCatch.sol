contract C {
    function g() external returns (uint) {}

    function f() public {
        try this.g() returns (uint v) {
        }
        uint x = 1;
    }
}
