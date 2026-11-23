contract C {
    function f() public pure returns (uint r) {
        assembly {
            let days := 1
            let storage, memory := g()
            function type(payable) -> new {
                new := add(payable, days)
            }
            r := type(storage)
            memory := 3
        }
    }
}
