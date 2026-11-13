package me.serce.solidity.ide.annotation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import me.serce.solidity.lang.psi.*

/**
 * The suffixes inline assembly allows on Solidity variables (solc's rules):
 * - `.slot` and `.offset` on storage variables and local storage pointers, `.offset` also on dynamic calldata arrays;
 * - `.length` on dynamic calldata arrays;
 * - `.address` and `.selector` on local external function pointers.
 *
 * Only reported when the kind of the variable is known for sure.
 */
object YulSuffixChecker {
  private val SUFFIXES = setOf("slot", "offset", "length", "address", "selector")

  /** What a Solidity variable is, as far as inline assembly is concerned. */
  private enum class Kind { STORAGE, CALLDATA_DYNAMIC, CALLDATA_STATIC, MEMORY, VALUE, EXTERNAL_FUNCTION, CONSTANT }

  fun check(path: SolYulPath, holder: AnnotationHolder) {
    val children = path.node.getChildren(null)
    // `x.suffix`: identifier, `.`, suffix
    if (children.size != 3 || children[1].text != ".") return
    val suffix = children[2].text
    if (suffix !in SUFFIXES) return
    val declaration = (path as? SolReferenceElement)?.reference?.resolve() ?: return
    val kind = kindOf(declaration) ?: return
    val allowed = when (suffix) {
      "slot" -> kind == Kind.STORAGE
      "offset" -> kind == Kind.STORAGE || kind == Kind.CALLDATA_DYNAMIC || kind == Kind.CALLDATA_STATIC
      "length" -> kind == Kind.CALLDATA_DYNAMIC || kind == Kind.CALLDATA_STATIC
      else -> kind == Kind.EXTERNAL_FUNCTION
    }
    if (allowed) return
    val message = when (suffix) {
      "slot" -> "'.slot' can only be used with storage variables and storage pointers"
      "offset" -> "'.offset' can only be used with storage variables, storage pointers and calldata arrays"
      "length" -> "'.length' can only be used with dynamic calldata arrays"
      else -> "'.$suffix' can only be used with external function pointers"
    }
    holder.newAnnotation(HighlightSeverity.ERROR, message).range(children[2].textRange).create()
  }

  private fun kindOf(declaration: PsiElement): Kind? = when (declaration) {
    is SolStateVariableDeclaration ->
      if (declaration.mutationModifierList.any { it.text == "constant" || it.text == "immutable" }) Kind.CONSTANT else Kind.STORAGE
    is SolConstantVariable -> Kind.CONSTANT
    is SolVariableDeclaration -> localKind(declaration.typeName, declaration.storageLocationSpecifier)
    is SolParameterDef -> localKind(declaration.typeName, declaration.storageLocationSpecifier)
    is SolTypedDeclarationItem -> localKind(declaration.typeName, declaration.storageLocationSpecifier)
    else -> null
  }

  private fun localKind(typeName: SolTypeName?, declared: SolStorageLocationSpecifier?): Kind? {
    typeName ?: return null
    val location = (declared ?: typeName.children.filterIsInstance<SolStorageLocationSpecifier>().lastOrNull())?.text
    return when (typeName) {
      is SolFunctionTypeName ->
        if (typeName.visibilitySpecifierList.any { it.text == "external" }) Kind.EXTERNAL_FUNCTION else Kind.VALUE
      is SolElementaryTypeName -> when (typeName.text) {
        "string", "bytes" -> referenceKind(location, dynamic = true)
        "var" -> null
        else -> Kind.VALUE
      }
      is SolBytesArrayTypeName -> referenceKind(location, dynamic = true)
      is SolArrayTypeName -> referenceKind(location, dynamic = typeName.expression == null)
      is SolMappingTypeName -> if (location == "storage") Kind.STORAGE else null
      is SolUserDefinedLocationTypeName -> {
        val type = typeName.children.filterIsInstance<SolUserDefinedTypeName>().firstOrNull() ?: return null
        when (type.reference?.resolve()) {
          is SolStructDefinition -> referenceKind(location, dynamic = false)
          is SolContractDefinition, is SolEnumDefinition, is SolUserDefinedValueTypeDefinition -> Kind.VALUE
          else -> null
        }
      }
      else -> null
    }
  }

  private fun referenceKind(location: String?, dynamic: Boolean): Kind? = when (location) {
    "storage" -> Kind.STORAGE
    "memory" -> Kind.MEMORY
    "calldata" -> if (dynamic) Kind.CALLDATA_DYNAMIC else Kind.CALLDATA_STATIC
    else -> null
  }
}
