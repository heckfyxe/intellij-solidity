package me.serce.solidity.ide.inspections

import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language

/** Valid, realistic code must not trigger any of the compiler-mirroring inspections. */
class CompilerChecksRealisticCodeTest : SolTestBase() {
  private fun checkClean(@Language("Solidity") code: String) {
    myFixture.configureByText("main.sol", code)
    myFixture.enableInspections(
      StateMutabilityInspection::class.java,
      RestrictableStateMutabilityInspection::class.java,
      ContractShouldBeAbstractInspection::class.java,
      MissingDataLocationInspection::class.java
    )
    myFixture.checkHighlighting(true, false, true)
  }

  fun testErc20WithOwnable() = checkClean("""
    pragma solidity ^0.8.20;

    interface IERC20 {
        event Transfer(address indexed from, address indexed to, uint256 value);
        event Approval(address indexed owner, address indexed spender, uint256 value);
        function totalSupply() external view returns (uint256);
        function balanceOf(address account) external view returns (uint256);
        function transfer(address to, uint256 value) external returns (bool);
        function allowance(address owner, address spender) external view returns (uint256);
        function approve(address spender, uint256 value) external returns (bool);
        function transferFrom(address from, address to, uint256 value) external returns (bool);
    }

    interface IERC20Metadata is IERC20 {
        function name() external view returns (string memory);
        function symbol() external view returns (string memory);
        function decimals() external view returns (uint8);
    }

    abstract contract Context {
        function _msgSender() internal view virtual returns (address) {
            return msg.sender;
        }
        function _msgData() internal view virtual returns (bytes calldata) {
            return msg.data;
        }
    }

    abstract contract Ownable is Context {
        address private _owner;
        error OwnableUnauthorizedAccount(address account);
        event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

        constructor(address initialOwner) {
            _transferOwnership(initialOwner);
        }
        modifier onlyOwner() {
            _checkOwner();
            _;
        }
        function owner() public view virtual returns (address) {
            return _owner;
        }
        function _checkOwner() internal view virtual {
            if (owner() != _msgSender()) {
                revert OwnableUnauthorizedAccount(_msgSender());
            }
        }
        function renounceOwnership() public virtual onlyOwner {
            _transferOwnership(address(0));
        }
        function _transferOwnership(address newOwner) internal virtual {
            address oldOwner = _owner;
            _owner = newOwner;
            emit OwnershipTransferred(oldOwner, newOwner);
        }
    }

    contract ERC20 is Context, IERC20, IERC20Metadata {
        mapping(address account => uint256) private _balances;
        mapping(address account => mapping(address spender => uint256)) private _allowances;
        uint256 private _totalSupply;
        string private _name;
        string private _symbol;

        constructor(string memory name_, string memory symbol_) {
            _name = name_;
            _symbol = symbol_;
        }
        function name() public view virtual returns (string memory) { return _name; }
        function symbol() public view virtual returns (string memory) { return _symbol; }
        function decimals() public view virtual returns (uint8) { return 18; }
        function totalSupply() public view virtual returns (uint256) { return _totalSupply; }
        function balanceOf(address account) public view virtual returns (uint256) { return _balances[account]; }
        function transfer(address to, uint256 value) public virtual returns (bool) {
            address owner = _msgSender();
            _transfer(owner, to, value);
            return true;
        }
        function allowance(address owner, address spender) public view virtual returns (uint256) {
            return _allowances[owner][spender];
        }
        function approve(address spender, uint256 value) public virtual returns (bool) {
            _allowances[_msgSender()][spender] = value;
            emit Approval(_msgSender(), spender, value);
            return true;
        }
        function transferFrom(address from, address to, uint256 value) public virtual returns (bool) {
            uint256 current = allowance(from, _msgSender());
            if (current != type(uint256).max) {
                unchecked { _allowances[from][_msgSender()] = current - value; }
            }
            _transfer(from, to, value);
            return true;
        }
        function _transfer(address from, address to, uint256 value) internal {
            _balances[from] -= value;
            _balances[to] += value;
            emit Transfer(from, to, value);
        }
        function _mint(address account, uint256 value) internal {
            _totalSupply += value;
            _balances[account] += value;
            emit Transfer(address(0), account, value);
        }
    }

    contract MyToken is ERC20, Ownable {
        constructor() ERC20("My", "MY") Ownable(msg.sender) {}
        function mint(address to, uint256 amount) public onlyOwner {
            _mint(to, amount);
        }
    }
  """)

  fun testLibrariesUsingForAndAssembly() = checkClean("""
    pragma solidity ^0.8.20;

    library EnumerableSet {
        struct Set {
            bytes32[] _values;
            mapping(bytes32 value => uint256) _positions;
        }
        function _add(Set storage set, bytes32 value) private returns (bool) {
            if (!_contains(set, value)) {
                set._values.push(value);
                set._positions[value] = set._values.length;
                return true;
            }
            return false;
        }
        function _contains(Set storage set, bytes32 value) private view returns (bool) {
            return set._positions[value] != 0;
        }
        function _length(Set storage set) private view returns (uint256) {
            return set._values.length;
        }
        struct AddressSet { Set _inner; }
        function add(AddressSet storage set, address value) internal returns (bool) {
            return _add(set._inner, bytes32(uint256(uint160(value))));
        }
        function length(AddressSet storage set) internal view returns (uint256) {
            return _length(set._inner);
        }
    }

    library StorageSlot {
        struct AddressSlot { address value; }
        function getAddressSlot(bytes32 slot) internal pure returns (AddressSlot storage r) {
            assembly { r.slot := slot }
        }
    }

    library Math {
        function max(uint256 a, uint256 b) internal pure returns (uint256) {
            return a > b ? a : b;
        }
    }

    contract Registry {
        using EnumerableSet for EnumerableSet.AddressSet;
        using Math for uint256;

        EnumerableSet.AddressSet private _members;
        uint256 private _limit;

        function join() external returns (bool) {
            require(_members.length() < _limit.max(10), "full");
            return _members.add(msg.sender);
        }
        function count() external view returns (uint256) {
            return _members.length();
        }
        function implementation() external view returns (address) {
            return StorageSlot.getAddressSlot(bytes32(uint256(1))).value;
        }
        function chainId() external view returns (uint256 id) {
            assembly { id := chainid() }
        }
        function encode(uint256[] calldata values, string calldata label) external pure returns (bytes memory) {
            uint256[] memory copy = values;
            return abi.encode(copy, label);
        }
    }
  """)
}
