package me.serce.solidity.lang.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolNamedElement
import me.serce.solidity.lang.psi.impl.SolImportPathElement

/** A name-keyed stub index whose version follows the Solidity stub version. */
abstract class SolStringStubIndex<T : PsiElement>(private val key: StubIndexKey<String, T>) : StringStubIndexExtension<T>() {
  override fun getVersion(): Int = SolidityFileStub.Type.stubVersion
  override fun getKey(): StubIndexKey<String, T> = key
}

class SolGotoClassIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolGotoClassIndex::class.java.canonicalName)
  }
}

class SolModifierIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolModifierIndex::class.java.canonicalName)
  }
}

class SolNamedElementIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolNamedElementIndex::class.java.canonicalName)
  }
}

class SolEventIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolEventIndex::class.java.canonicalName)
  }
}

class SolErrorIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolErrorIndex::class.java.canonicalName)
  }
}

class SolFunctionIndex : SolStringStubIndex<SolNamedElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolNamedElement> = StubIndexKey.createIndexKey(SolFunctionIndex::class.java.canonicalName)
  }
}

class SolImportIndex : SolStringStubIndex<SolImportPathElement>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolImportPathElement> = StubIndexKey.createIndexKey(SolImportIndex::class.java.canonicalName)
  }
}

/**
 * Maps the names of inherited contracts to the contracts that inherit from them.
 *
 * This is the reverse index that powers "find implementations" without scanning every reference in the project.
 */
class SolInheritanceIndex : SolStringStubIndex<SolContractDefinition>(KEY) {
  companion object {
    val KEY: StubIndexKey<String, SolContractDefinition> = StubIndexKey.createIndexKey(SolInheritanceIndex::class.java.canonicalName)
  }
}
