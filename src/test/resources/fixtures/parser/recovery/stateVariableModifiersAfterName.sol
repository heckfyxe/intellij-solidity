contract Hack {
    address addr private immutable;
    uint x = 1 internal;

    constructor(address _addr) {
        addr = _addr;
    }
}

contract D {
    function f() public {
        uint y = 2 memory;
        y = 3 foo;
    }
}
