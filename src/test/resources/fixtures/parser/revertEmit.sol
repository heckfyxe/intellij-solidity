library L {
    error Err(uint a);
    event Ev(uint a);
}

contract C {
    error E(uint a);
    event V(uint a);

    function f(uint x) public {
        if (x == 0) revert("message");
        if (x == 1) revert();
        if (x == 2) revert E(1);
        if (x == 3) revert L.Err(2);
        if (x == 4) revert(string.concat("a", "b"));
        emit V(1);
        emit L.Ev(2);
        emit V({a: 3});
    }
}
