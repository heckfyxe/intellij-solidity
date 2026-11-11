package me.serce.solidity.lang.stubs

import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import me.serce.solidity.lang.SolidityLanguage
import me.serce.solidity.lang.psi.SolElement
import me.serce.solidity.lang.psi.SolNamedElement

interface SolNamedStub {
  val name: String?
}

abstract class SolStubElementType<S : StubElement<*>, P : SolElement>(debugName: String)
  : IStubElementType<S, P>(debugName, SolidityLanguage) {
  final override fun getExternalId(): String = "solidity.${super.toString()}"
}

/** Element type for stubs that only store the element's name. */
abstract class SolNamedStubElementType<S, P : SolNamedElement>(
  debugName: String,
  private val newStub: (StubElement<*>?, IStubElementType<*, *>, String?) -> S,
  private val newPsi: (S, IStubElementType<*, *>) -> P,
  private val index: (IndexSink, S) -> Unit,
) : SolStubElementType<S, P>(debugName) where S : StubElement<*>, S : SolNamedStub {
  override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): S =
    newStub(parentStub, this, dataStream.readNameAsString())

  override fun serialize(stub: S, dataStream: StubOutputStream) = dataStream.writeName(stub.name)

  override fun createPsi(stub: S): P = newPsi(stub, this)

  override fun createStub(psi: P, parentStub: StubElement<*>?): S = newStub(parentStub, this, psi.name)

  override fun indexStub(stub: S, sink: IndexSink) = index(sink, stub)
}
