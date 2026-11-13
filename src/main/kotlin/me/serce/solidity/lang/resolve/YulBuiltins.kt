package me.serce.solidity.lang.resolve

/**
 * The built-in functions of Yul's EVM dialect, see https://docs.soliditylang.org/en/latest/yul.html#evm-dialect.
 *
 * Built-ins have no declaration to resolve to; references to them are known and never reported as unresolved.
 */
object YulBuiltins {
  class Builtin(val name: String, val params: List<String>, val returns: Boolean, val deprecated: Boolean = false) {
    val signature: String get() = params.joinToString(", ", "(", ")")
  }

  private fun b(name: String, params: String, returns: Boolean = true, deprecated: Boolean = false) =
    Builtin(name, if (params.isEmpty()) emptyList() else params.split(",").map { it.trim() }, returns, deprecated)

  val all: List<Builtin> = listOf(
    b("stop", "", false),
    b("add", "x, y"), b("sub", "x, y"), b("mul", "x, y"), b("div", "x, y"), b("sdiv", "x, y"),
    b("mod", "x, y"), b("smod", "x, y"), b("exp", "x, y"), b("not", "x"),
    b("lt", "x, y"), b("gt", "x, y"), b("slt", "x, y"), b("sgt", "x, y"), b("eq", "x, y"), b("iszero", "x"),
    b("and", "x, y"), b("or", "x, y"), b("xor", "x, y"), b("byte", "n, x"),
    b("shl", "x, y"), b("shr", "x, y"), b("sar", "x, y"), b("clz", "x"),
    b("addmod", "x, y, m"), b("mulmod", "x, y, m"), b("signextend", "i, x"), b("keccak256", "p, n"),
    b("pc", "", deprecated = true),
    b("pop", "x", false),
    b("mload", "p"), b("mstore", "p, v", false), b("mstore8", "p, v", false),
    b("sload", "p"), b("sstore", "p, v", false), b("tload", "p"), b("tstore", "p, v", false),
    b("msize", ""), b("gas", ""), b("address", ""), b("balance", "a"), b("selfbalance", ""),
    b("caller", ""), b("callvalue", ""), b("calldataload", "p"), b("calldatasize", ""),
    b("calldatacopy", "t, f, s", false), b("codesize", ""), b("codecopy", "t, f, s", false),
    b("extcodesize", "a"), b("extcodecopy", "a, t, f, s", false), b("returndatasize", ""),
    b("returndatacopy", "t, f, s", false), b("mcopy", "t, f, s", false), b("extcodehash", "a"),
    b("create", "v, p, n"), b("create2", "v, p, n, s"),
    b("call", "g, a, v, in, insize, out, outsize"),
    b("callcode", "g, a, v, in, insize, out, outsize", deprecated = true),
    b("delegatecall", "g, a, in, insize, out, outsize"),
    b("staticcall", "g, a, in, insize, out, outsize"),
    b("return", "p, s", false), b("revert", "p, s", false),
    b("selfdestruct", "a", false, deprecated = true), b("invalid", "", false),
    b("log0", "p, s", false), b("log1", "p, s, t1", false), b("log2", "p, s, t1, t2", false),
    b("log3", "p, s, t1, t2, t3", false), b("log4", "p, s, t1, t2, t3, t4", false),
    b("chainid", ""), b("basefee", ""), b("blobbasefee", ""), b("slotnum", ""), b("origin", ""), b("gasprice", ""),
    b("blockhash", "b"), b("blobhash", "i"), b("coinbase", ""), b("timestamp", ""), b("number", ""),
    b("difficulty", "", deprecated = true), b("prevrandao", ""), b("gaslimit", ""),
    // object and compiler built-ins
    b("datasize", "\"name\""), b("dataoffset", "\"name\""), b("datacopy", "t, f, l", false),
    b("setimmutable", "offset, \"name\", value", false), b("loadimmutable", "\"name\""),
    b("linkersymbol", "\"library_id\""), b("memoryguard", "size"),
  )

  private val byName: Map<String, Builtin> = all.associateBy { it.name }

  /** `verbatim_<n>i_<m>o` with n inputs and m outputs, each between 0 and 99. */
  private val verbatim = Regex("verbatim_\\d{1,2}i_\\d{1,2}o")

  operator fun get(name: String): Builtin? = byName[name]

  fun isBuiltin(name: String): Boolean = name in byName || verbatim.matches(name)
}
