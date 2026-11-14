contract C {
    uint a
    mapping(uint => address) s_approved;
    address _owner;

    function f(uint _tokenId) public view returns (uint) {
        require(_owner == msg.sender || s_approved[_tokenId] == msg.sender)
        uint x = a + 1
        x = x * 2
        emit E(x)
        return x
    }

    function g() public {
        a++
    }
}
