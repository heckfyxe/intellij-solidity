contract A {}

contract B is {
}

contract C is A, {
    struct S {
        uint a
        uint b;
    }

    mapping(uint, uint) m;

    event E(uint a,);

    function f(uint a uint b) public {}

    function g() public returns uint {}

    function t(function () external returns uint cb, uint y) public {
        try this.g() returns uint {
        } catch {
        }
    }

    function h() external view override(A, ) {}

    function i(uint x) public {
        if (x > 1 {
        }
        for (uint j = 0; j < x; j++ {
        }
        i({: 1});
        i({x: 1, : 2});
    }
}

interface I {
    function a() external returns (bool)
    function b() external returns (bool);
}
