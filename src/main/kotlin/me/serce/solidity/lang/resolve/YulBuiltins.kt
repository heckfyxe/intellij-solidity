package me.serce.solidity.lang.resolve

/**
 * The built-in functions of Yul's EVM dialect, see https://docs.soliditylang.org/en/latest/yul.html#evm-dialect.
 *
 * Built-ins have no declaration to resolve to; references to them are known and never reported as unresolved.
 */
object YulBuiltins {
  class Builtin(
    val name: String,
    val params: List<String>,
    val returns: Boolean,
    val description: String,
    /** The first EVM version that has it, null when it has always been there. */
    val since: EvmVersion? = null,
    /** The EVM version it was removed in (`difficulty`, renamed to `prevrandao`). */
    val until: EvmVersion? = null,
    val deprecated: Boolean = false,
  ) {
    val signature: String get() = params.joinToString(", ", "(", ")")
  }

  private fun b(
    name: String,
    params: String,
    returns: Boolean,
    description: String,
    since: EvmVersion? = null,
    until: EvmVersion? = null,
    deprecated: Boolean = false,
  ) = Builtin(
    name, if (params.isEmpty()) emptyList() else params.split(",").map { it.trim() }, returns, description, since, until, deprecated
  )

  private const val V = true
  private const val NO = false

  val all: List<Builtin> = listOf(
    b("stop", "", NO, "stop execution, identical to return(0, 0)"),
    b("add", "x, y", V, "x + y"),
    b("sub", "x, y", V, "x - y"),
    b("mul", "x, y", V, "x * y"),
    b("div", "x, y", V, "x / y or 0 if y == 0"),
    b("sdiv", "x, y", V, "x / y, for signed numbers in two's complement, 0 if y == 0"),
    b("mod", "x, y", V, "x % y, 0 if y == 0"),
    b("smod", "x, y", V, "x % y, for signed numbers in two's complement, 0 if y == 0"),
    b("exp", "x, y", V, "x to the power of y"),
    b("not", "x", V, "bitwise \"not\" of x (every bit of x is negated)"),
    b("lt", "x, y", V, "1 if x < y, 0 otherwise"),
    b("gt", "x, y", V, "1 if x > y, 0 otherwise"),
    b("slt", "x, y", V, "1 if x < y, 0 otherwise, for signed numbers in two's complement"),
    b("sgt", "x, y", V, "1 if x > y, 0 otherwise, for signed numbers in two's complement"),
    b("eq", "x, y", V, "1 if x == y, 0 otherwise"),
    b("iszero", "x", V, "1 if x == 0, 0 otherwise"),
    b("and", "x, y", V, "bitwise \"and\" of x and y"),
    b("or", "x, y", V, "bitwise \"or\" of x and y"),
    b("xor", "x, y", V, "bitwise \"xor\" of x and y"),
    b("byte", "n, x", V, "nth byte of x, where the most significant byte is the 0th byte"),
    b("shl", "x, y", V, "logical shift left y by x bits", EvmVersion.CONSTANTINOPLE),
    b("shr", "x, y", V, "logical shift right y by x bits", EvmVersion.CONSTANTINOPLE),
    b("sar", "x, y", V, "signed arithmetic shift right y by x bits", EvmVersion.CONSTANTINOPLE),
    b("clz", "x", V, "number of leading zero bits of x, 256 if x == 0", EvmVersion.OSAKA),
    b("addmod", "x, y, m", V, "(x + y) % m with arbitrary precision arithmetic, 0 if m == 0"),
    b("mulmod", "x, y, m", V, "(x * y) % m with arbitrary precision arithmetic, 0 if m == 0"),
    b("signextend", "i, x", V, "sign extend from (i*8+7)th bit counting from least significant"),
    b("keccak256", "p, n", V, "keccak(mem[p...(p+n)))"),
    b("pc", "", V, "current position in code", deprecated = true),
    b("pop", "x", NO, "discard value x"),
    b("mload", "p", V, "mem[p...(p+32))"),
    b("mstore", "p, v", NO, "mem[p...(p+32)) := v"),
    b("mstore8", "p, v", NO, "mem[p] := v & 0xff (only modifies a single byte)"),
    b("sload", "p", V, "storage[p]"),
    b("sstore", "p, v", NO, "storage[p] := v"),
    b("tload", "p", V, "transientStorage[p]", EvmVersion.CANCUN),
    b("tstore", "p, v", NO, "transientStorage[p] := v", EvmVersion.CANCUN),
    b("msize", "", V, "size of memory, i.e. largest accessed memory index"),
    b("gas", "", V, "gas still available to execution"),
    b("address", "", V, "address of the current contract / execution context"),
    b("balance", "a", V, "wei balance at address a"),
    b("selfbalance", "", V, "equivalent to balance(address()), but cheaper", EvmVersion.ISTANBUL),
    b("caller", "", V, "call sender (excluding delegatecall)"),
    b("callvalue", "", V, "wei sent together with the current call"),
    b("calldataload", "p", V, "call data starting from position p (32 bytes)"),
    b("calldatasize", "", V, "size of call data in bytes"),
    b("calldatacopy", "t, f, s", NO, "copy s bytes from calldata at position f to mem at position t"),
    b("codesize", "", V, "size of the code of the current contract / execution context"),
    b("codecopy", "t, f, s", NO, "copy s bytes from code at position f to mem at position t"),
    b("extcodesize", "a", V, "size of the code at address a"),
    b("extcodecopy", "a, t, f, s", NO, "like codecopy(t, f, s) but take code at address a"),
    b("returndatasize", "", V, "size of the last returndata", EvmVersion.BYZANTIUM),
    b("returndatacopy", "t, f, s", NO, "copy s bytes from returndata at position f to mem at position t", EvmVersion.BYZANTIUM),
    b("mcopy", "t, f, s", NO, "copy s bytes from mem at position f to mem at position t", EvmVersion.CANCUN),
    b("extcodehash", "a", V, "code hash of address a", EvmVersion.CONSTANTINOPLE),
    b("create", "v, p, n", V, "create new contract with code mem[p...(p+n)) and send v wei and return the new address; returns 0 on error"),
    b("create2", "v, p, n, s", V, "create new contract with code mem[p...(p+n)) at address keccak256(0xff . this . s . keccak256(mem[p...(p+n))) and send v wei and return the new address; returns 0 on error", EvmVersion.CONSTANTINOPLE),
    b("call", "g, a, v, in, insize, out, outsize", V, "call contract at address a with input mem[in...(in+insize)) providing g gas and v wei and output area mem[out...(out+outsize)) returning 0 on error (e.g. out of gas) and 1 on success"),
    b("callcode", "g, a, v, in, insize, out, outsize", V, "identical to call but only use the code from a and stay in the context of the current contract otherwise", deprecated = true),
    b("delegatecall", "g, a, in, insize, out, outsize", V, "identical to callcode but also keep caller and callvalue"),
    b("staticcall", "g, a, in, insize, out, outsize", V, "identical to call(g, a, 0, in, insize, out, outsize) but do not allow state modifications", EvmVersion.BYZANTIUM),
    b("return", "p, s", NO, "end execution, return data mem[p...(p+s))"),
    b("revert", "p, s", NO, "end execution, revert state changes, return data mem[p...(p+s))", EvmVersion.BYZANTIUM),
    b("selfdestruct", "a", NO, "end execution, destroy current contract and send funds to a", deprecated = true),
    b("invalid", "", NO, "end execution with invalid instruction"),
    b("log0", "p, s", NO, "log data mem[p...(p+s))"),
    b("log1", "p, s, t1", NO, "log data mem[p...(p+s)) with topic t1"),
    b("log2", "p, s, t1, t2", NO, "log data mem[p...(p+s)) with topics t1, t2"),
    b("log3", "p, s, t1, t2, t3", NO, "log data mem[p...(p+s)) with topics t1, t2, t3"),
    b("log4", "p, s, t1, t2, t3, t4", NO, "log data mem[p...(p+s)) with topics t1, t2, t3, t4"),
    b("chainid", "", V, "ID of the executing chain (EIP-1344)", EvmVersion.ISTANBUL),
    b("basefee", "", V, "current block's base fee (EIP-3198 and EIP-1559)", EvmVersion.LONDON),
    b("blobbasefee", "", V, "current block's blob base fee (EIP-7516 and EIP-4844)", EvmVersion.CANCUN),
    b("slotnum", "", V, "current beacon chain slot number", EvmVersion.AMSTERDAM),
    b("origin", "", V, "transaction sender"),
    b("gasprice", "", V, "gas price of the transaction"),
    b("blockhash", "b", V, "hash of block nr b - only for last 256 blocks excluding current"),
    b("blobhash", "i", V, "versioned hash of the transaction's i-th blob, 0 if blob does not exist", EvmVersion.CANCUN),
    b("coinbase", "", V, "current mining beneficiary"),
    b("timestamp", "", V, "timestamp of the current block in seconds since the epoch"),
    b("number", "", V, "current block number"),
    b("difficulty", "", V, "difficulty of the current block, replaced by prevrandao since Paris", until = EvmVersion.PARIS, deprecated = true),
    b("prevrandao", "", V, "randomness provided by the beacon chain", EvmVersion.PARIS),
    b("gaslimit", "", V, "block gas limit of the current block"),
    // object and compiler built-ins
    b("datasize", "\"name\"", V, "size of the data object or sub-object with the given name"),
    b("dataoffset", "\"name\"", V, "offset of the data object or sub-object with the given name in the data area"),
    b("datacopy", "t, f, l", NO, "copy l bytes from the data area at position f to memory at position t, identical to codecopy"),
    b("setimmutable", "offset, \"name\", value", NO, "write value to every position of the immutable with the given name in the code copied to memory at offset"),
    b("loadimmutable", "\"name\"", V, "the value of the immutable with the given name"),
    b("linkersymbol", "\"library_id\"", V, "the address of the library with the given fully qualified name, filled in by the linker"),
    b("memoryguard", "size", V, "the size, telling the compiler that only memory in [0, size) is used outside of the compiler's control"),
  )

  private val byName: Map<String, Builtin> = all.associateBy { it.name }

  /** `verbatim_<n>i_<m>o` with n inputs and m outputs, each between 0 and 99. */
  private val verbatim = Regex("verbatim_\\d{1,2}i_\\d{1,2}o")

  operator fun get(name: String): Builtin? = byName[name]

  fun isBuiltin(name: String): Boolean = name in byName || verbatim.matches(name)

  /** The reason [builtin] can't be used with [evm], or null when it can. */
  fun unavailabilityReason(builtin: Builtin, evm: EvmVersion): String? {
    builtin.since?.let { since ->
      if (evm < since) {
        val rename = if (builtin.name == "prevrandao") ", use 'difficulty' instead" else ""
        return "'${builtin.name}' is only available for ${since.displayName}-compatible VMs (the EVM version is ${evm.id})$rename"
      }
    }
    builtin.until?.let { until ->
      if (evm >= until) return "'${builtin.name}' was replaced by 'prevrandao' in the ${until.id} EVM version (the EVM version is ${evm.id})"
    }
    return null
  }
}

/** The EVM versions known to solc, in order. */
enum class EvmVersion(val id: String) {
  HOMESTEAD("homestead"),
  TANGERINE_WHISTLE("tangerineWhistle"),
  SPURIOUS_DRAGON("spuriousDragon"),
  BYZANTIUM("byzantium"),
  CONSTANTINOPLE("constantinople"),
  PETERSBURG("petersburg"),
  ISTANBUL("istanbul"),
  BERLIN("berlin"),
  LONDON("london"),
  PARIS("paris"),
  SHANGHAI("shanghai"),
  CANCUN("cancun"),
  PRAGUE("prague"),
  OSAKA("osaka"),
  AMSTERDAM("amsterdam");

  val displayName: String get() = id.replaceFirstChar { it.uppercase() }

  companion object {
    /** Parses solc's or forge's spelling (`tangerineWhistle`, `tangerine_whistle`, `Cancun`, ...). */
    fun parse(text: String?): EvmVersion? {
      val key = text?.lowercase()?.replace("_", "")?.replace("-", "")?.trim() ?: return null
      return entries.firstOrNull { it.id.lowercase() == key }
    }
  }
}
