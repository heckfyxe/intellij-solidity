package me.serce.solidity.lang.stubs

import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.psi.util.childrenOfType
import me.serce.solidity.lang.SolidityLanguage
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.*

class SolidityFileStub(file: SolidityFile?) : PsiFileStubImpl<SolidityFile>(file) {
  override fun getType() = Type

  object Type : IStubFileElementType<SolidityFileStub>(SolidityLanguage) {
    // bump version every time stub tree changes
    override fun getStubVersion() = 21

    override fun getBuilder(): StubBuilder = object : DefaultStubBuilder() {
      override fun createStubForFile(file: PsiFile) = SolidityFileStub(file as SolidityFile)
    }

    override fun serialize(stub: SolidityFileStub, dataStream: StubOutputStream) {}

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) = SolidityFileStub(null)

    override fun getExternalId(): String = "Solidity.file"
  }
}

fun factory(name: String): SolStubElementType<*, *> = when (name) {
  "ENUM_DEFINITION" -> SolEnumDefStub.Type
  "CONTRACT_DEFINITION" -> SolContractOrLibDefStub.Type
  "FUNCTION_DEFINITION" -> SolFunctionDefStub.Type
  "MODIFIER_DEFINITION" -> SolModifierDefStub.Type
  "STRUCT_DEFINITION" -> SolStructDefStub.Type
  "EVENT_DEFINITION" -> SolEventDefStub.Type
  "ERROR_DEFINITION" -> SolErrorDefStub.Type
  "USER_DEFINED_VALUE_TYPE_DEFINITION" -> SolUserDefinedValueTypeDefStub.Type
  "STATE_VARIABLE_DECLARATION" -> SolStateVarDeclStub.Type
  "CONSTANT_VARIABLE_DECLARATION" -> SolConstantVariableDeclStub.Type
  "IMPORT_PATH" -> SolImportPathDefStub.Type
  "IMPORT_ALIAS" -> SolImportAliasDefStub.Type

  "ELEMENTARY_TYPE_NAME" -> SolTypeRefStub.Type("ELEMENTARY_TYPE_NAME", ::SolElementaryTypeNameImpl)
  "MAPPING_TYPE_NAME" -> SolTypeRefStub.Type("MAPPING_TYPE_NAME", ::SolMappingTypeNameImpl)
  "FUNCTION_TYPE_NAME" -> SolTypeRefStub.Type("FUNCTION_TYPE_NAME", ::SolFunctionTypeNameImpl)
  "ARRAY_TYPE_NAME" -> SolTypeRefStub.Type("ARRAY_TYPE_NAME", ::SolArrayTypeNameImpl)
  "BYTES_ARRAY_TYPE_NAME" -> SolTypeRefStub.Type("BYTES_ARRAY_TYPE_NAME", ::SolBytesArrayTypeNameImpl)
  "USER_DEFINED_LOCATION_TYPE_NAME" -> SolTypeRefStub.Type("USER_DEFINED_LOCATION_TYPE_NAME", ::SolUserDefinedLocationTypeNameImpl)

  "USER_DEFINED_TYPE_NAME" -> SolTypeRefStub.Type("USER_DEFINED_TYPE_NAME", ::SolUserDefinedTypeNameImpl)

  else -> error("Unknown element $name")
}

class SolEnumDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolEnumDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolEnumDefStub, SolEnumDefinition>(
    "ENUM_DEFINITION", ::SolEnumDefStub, ::SolEnumDefinitionImpl, IndexSink::indexEnumDef
  )
}

class SolUserDefinedValueTypeDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolUserDefinedValueTypeDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolUserDefinedValueTypeDefStub, SolUserDefinedValueTypeDefinition>(
    "USER_DEFINED_VALUE_TYPE_DEFINITION", ::SolUserDefinedValueTypeDefStub, ::SolUserDefinedValueTypeDefinitionImpl, IndexSink::indexUserDefinedValueTypeDef
  )
}

class SolFunctionDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolFunctionDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolFunctionDefStub, SolFunctionDefinition>(
    "FUNCTION_DEFINITION", ::SolFunctionDefStub, ::SolFunctionDefinitionImpl, IndexSink::indexFunctionDef
  )
}

class SolModifierDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolModifierDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolModifierDefStub, SolModifierDefinition>(
    "MODIFIER_DEFINITION", ::SolModifierDefStub, ::SolModifierDefinitionImpl, IndexSink::indexModifierDef
  )
}

class SolStructDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolStructDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolStructDefStub, SolStructDefinition>(
    "STRUCT_DEFINITION", ::SolStructDefStub, ::SolStructDefinitionImpl, IndexSink::indexStructDef
  )
}

class SolStateVarDeclStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolStateVariableDeclaration>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolStateVarDeclStub, SolStateVariableDeclaration>(
    "STATE_VARIABLE_DECLARATION", ::SolStateVarDeclStub, ::SolStateVariableDeclarationImpl, IndexSink::indexStateVarDecl
  )
}

class SolConstantVariableDeclStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolConstantVariableDeclaration>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolConstantVariableDeclStub, SolConstantVariableDeclaration>(
    "CONSTANT_VARIABLE_DECLARATION", ::SolConstantVariableDeclStub, ::SolConstantVariableDeclarationImpl, IndexSink::indexConstantVariableDecl
  )
}

class SolContractOrLibDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?,
  val superNames: List<String>
) : StubBase<SolContractDefinition>(parent, elementType), SolNamedStub {

  object Type : SolStubElementType<SolContractOrLibDefStub, SolContractDefinition>("CONTRACT_DEFINITION") {
    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
      SolContractOrLibDefStub(parentStub, this, dataStream.readNameAsString(), dataStream.readSuperNames())

    override fun serialize(stub: SolContractOrLibDefStub, dataStream: StubOutputStream) = with(dataStream) {
      writeName(stub.name)
      writeSuperNames(stub.superNames)
    }

    override fun createPsi(stub: SolContractOrLibDefStub) = SolContractDefinitionImpl(stub, this)

    override fun createStub(psi: SolContractDefinition, parentStub: StubElement<*>?) =
      SolContractOrLibDefStub(parentStub, this, psi.name, psi.indexedSuperNames())

    override fun indexStub(stub: SolContractOrLibDefStub, sink: IndexSink) = sink.indexContractDef(stub)
  }
}

class SolTypeRefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>
) : StubBase<SolTypeName>(parent, elementType) {

  class Type<T : SolTypeName>(
    debugName: String,
    private val psiFactory: (SolTypeRefStub, IStubElementType<*, *>) -> T
  ) : SolStubElementType<SolTypeRefStub, T>(debugName) {
    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
      SolTypeRefStub(parentStub, this)

    override fun serialize(stub: SolTypeRefStub, dataStream: StubOutputStream) {}

    override fun createPsi(stub: SolTypeRefStub) = psiFactory(stub, this)

    override fun createStub(psi: T, parentStub: StubElement<*>?) = SolTypeRefStub(parentStub, this)

    override fun indexStub(stub: SolTypeRefStub, sink: IndexSink) {}
  }
}

class SolEventDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolEventDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolEventDefStub, SolEventDefinition>(
    "EVENT_DEFINITION", ::SolEventDefStub, ::SolEventDefinitionImpl, IndexSink::indexEventDef
  )
}

class SolErrorDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?
) : StubBase<SolErrorDefinition>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolErrorDefStub, SolErrorDefinition>(
    "ERROR_DEFINITION", ::SolErrorDefStub, ::SolErrorDefinitionImpl, IndexSink::indexErrorDef
  )
}

class SolImportPathDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?,
  val path: String?
) : StubBase<SolImportPathImpl>(parent, elementType), SolNamedStub {

  object Type : SolStubElementType<SolImportPathDefStub, SolImportPathImpl>("IMPORT_PATH") {
    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
      SolImportPathDefStub(parentStub, this, dataStream.readNameAsString(), dataStream.readUTFFast())

    override fun serialize(stub: SolImportPathDefStub, dataStream: StubOutputStream) = with(dataStream) {
      writeName(stub.name)
      writeUTFFast(stub.path ?: "")
    }

    override fun createPsi(stub: SolImportPathDefStub) = SolImportPathImpl(stub, this)

    override fun createStub(psi: SolImportPathImpl, parentStub: StubElement<*>?) = SolImportPathDefStub(parentStub, this, psi.name, psi.text)

    override fun indexStub(stub: SolImportPathDefStub, sink: IndexSink) = sink.indexImportPathDef(stub)
  }
}

class SolImportAliasDefStub(
  parent: StubElement<*>?,
  elementType: IStubElementType<*, *>,
  override val name: String?,
) : StubBase<SolImportAliasImpl>(parent, elementType), SolNamedStub {

  object Type : SolNamedStubElementType<SolImportAliasDefStub, SolImportAliasImpl>(
    "IMPORT_ALIAS", ::SolImportAliasDefStub, ::SolImportAliasImpl, IndexSink::indexImportAliasDef
  )
}

internal fun StubInputStream.readNameAsString(): String? = readName()?.string

/**
 * Names of the contracts this contract inherits from, as they should be indexed in [SolInheritanceIndex].
 *
 * Import aliases are resolved locally (aliases are file-scoped in Solidity) so that a contract inheriting
 * through an alias (e.g. `import {A as B} from "./a.sol"; contract C is B {}`) is indexed under the original
 * name `A`. This keeps the reverse lookup by contract name working without a project-wide reference search.
 */
private fun SolContractDefinition.indexedSuperNames(): List<String> {
  val aliases = containingFile.childrenOfType<SolImportDirective>()
    .flatMap { it.importAliasedPairList }
    .mapNotNull { pair ->
      val alias = pair.importAlias?.name ?: return@mapNotNull null
      val original = pair.userDefinedTypeName.name ?: return@mapNotNull null
      alias to original
    }
    .toMap()
  return inheritanceSpecifierList
    .mapNotNull { specifier ->
      val typeName = specifier.userDefinedTypeName ?: return@mapNotNull null
      val name = typeName.name ?: return@mapNotNull null
      // Only unqualified names can refer to a file-scoped import alias. A qualified name such as
      // `NS.Base` must keep its last segment (`Base`) even if another import renames `Other` to `Base`.
      if (typeName.findIdentifiers().size == 1) aliases[name] ?: name else name
    }
    .distinct()
}

private fun StubOutputStream.writeSuperNames(names: List<String>) {
  writeUTFFast(names.joinToString(" "))
}

private fun StubInputStream.readSuperNames(): List<String> =
  readUTFFast().takeIf { it.isNotEmpty() }?.split(" ") ?: emptyList()
