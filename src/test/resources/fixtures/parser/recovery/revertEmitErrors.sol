contract C {
    error E();
    event V();

    function f(uint x) public {
        revert x;
        emit x;
        revert E();
        emit V();
    }
}
