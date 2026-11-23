contract C {
    function receive() external payable {}
    function fallback() external {}

    receive() external payable {}
    fallback() external {}
}
