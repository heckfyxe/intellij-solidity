abstract contract Base {
    constructor(uint x) {}
    modifier onlyOwner() virtual;
}

library Lib {
    function f() external {}
}

contract C {
    constructor() Lib.Base(1) {}

    function g() public view returns (address, bytes4) {
        return (this.g.address, this.g.selector);
    }
}
