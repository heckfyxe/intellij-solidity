package me.serce.solidity.lang.stubs

import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubIndexKey
import me.serce.solidity.lang.psi.SolNamedElement

fun IndexSink.indexEnumDef(stub: SolEnumDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolGotoClassIndex.KEY)
}

fun IndexSink.indexUserDefinedValueTypeDef(stub: SolUserDefinedValueTypeDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolGotoClassIndex.KEY)
}

fun IndexSink.indexContractDef(stub: SolContractOrLibDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolGotoClassIndex.KEY)
  stub.superNames.forEach { occurrence(SolInheritanceIndex.KEY, it) }
}

fun IndexSink.indexStructDef(stub: SolStructDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolGotoClassIndex.KEY)
}

fun IndexSink.indexFunctionDef(stub: SolFunctionDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolFunctionIndex.KEY)
}

fun IndexSink.indexModifierDef(stub: SolModifierDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolModifierIndex.KEY)
}

fun IndexSink.indexStateVarDecl(stub: SolStateVarDeclStub) {
  indexName(stub, SolNamedElementIndex.KEY)
}

fun IndexSink.indexConstantVariableDecl(stub: SolConstantVariableDeclStub) {
  indexName(stub, SolNamedElementIndex.KEY)
}

fun IndexSink.indexEventDef(stub: SolEventDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolEventIndex.KEY, SolGotoClassIndex.KEY)
}

fun IndexSink.indexErrorDef(stub: SolErrorDefStub) {
  indexName(stub, SolNamedElementIndex.KEY, SolErrorIndex.KEY, SolGotoClassIndex.KEY)
}

fun IndexSink.indexImportPathDef(stub: SolImportPathDefStub) {
  indexName(stub, SolNamedElementIndex.KEY)
  stub.path?.let { occurrence(SolImportIndex.KEY, it) }
}

fun IndexSink.indexImportAliasDef(stub: SolImportAliasDefStub) {
  indexName(stub, SolNamedElementIndex.KEY)
}

private fun IndexSink.indexName(stub: SolNamedStub, vararg keys: StubIndexKey<String, SolNamedElement>) {
  val name = stub.name ?: return
  keys.forEach { occurrence(it, name) }
}
