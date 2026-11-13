package me.serce.solidity.ide.inspections

import com.intellij.psi.PsiFile
import me.serce.solidity.lang.core.solidityVersionRange

/**
 * Returns true when the file's `pragma solidity` admits a compiler older than `0.<minor>.0`.
 *
 * Inspections that mirror errors of modern compilers use it to stay silent on legacy code, where the same constructs
 * were legal (e.g. contracts with unimplemented functions were implicitly abstract before 0.6.0). A file without a
 * (parseable) `pragma solidity` is treated as modern code.
 */
fun PsiFile.allowsSolidityOlderThan(minor: Int): Boolean =
  solidityVersionRange()?.let { !it.isAtLeast("0.$minor.0") } ?: false
