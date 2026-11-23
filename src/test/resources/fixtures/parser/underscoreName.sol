contract C {
    uint _;

    modifier m() {
        _;
    }

    modifier n() {
        if (_ > 0) {
            _;
        }
    }

    function f() public {
        _ = 1;
        _ += 2;
    }
}
