package me.serce.solidity.ide.hints

import com.intellij.psi.tree.TokenSet
import me.serce.solidity.lang.types.SolInternalTypeFactory
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.*
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.SolHighlighter
import me.serce.solidity.ide.colors.SolColor
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolErrorDefMixin
import me.serce.solidity.lang.psi.parentOfType
import me.serce.solidity.lang.resolve.SolResolver
import me.serce.solidity.lang.resolve.YulBuiltins
import me.serce.solidity.lang.resolve.function.SolFunctionResolver
import me.serce.solidity.lang.types.findContract
import me.serce.solidity.lang.types.getSolType
import me.serce.solidity.lang.types.isBuiltin

const val NO_VALIDATION_TAG = "@custom:no_validation"

/** Whether the NatSpec of this declaration opts it out of argument validation. */
fun PsiElement.hasNoValidationTag() = comments().any { it.isNatSpecTag(NO_VALIDATION_TAG) }

private fun PsiElement.isNatSpecTag(tag: String) = elementType == SolidityTokenTypes.NAT_SPEC_TAG && text == tag

/** The documentation comments of this declaration (`///` lines and the last `/** */` block before it), in source order. */
fun PsiElement.comments(): List<PsiElement> {
  return CachedValuesManager.getCachedValue(this) {
    val builtin = isBuiltin()
    val nonSolElements = siblings(false, false)
      .takeWhile { it !is SolElement }.toList()
    val res = (if (!builtin) PsiDocumentManager.getInstance(project).getDocument(this.containingFile)?.let { document ->
      val tripleLines = nonSolElements.filter { it.text.startsWith("///") }.map { document.getLineNumber(it.textOffset) }.toSet()
      val tripleLineComments = nonSolElements.filter { tripleLines.contains(document.getLineNumber(it.startOffset)) }
      val blockComments = collectBlockComments(nonSolElements)
      tripleLineComments + blockComments
    } ?: emptyList()
    else {
      collectBlockComments(nonSolElements)
    }).filterIsInstance<PsiComment>().sortedBy { it.startOffset }
    CachedValueProvider.Result.create(res, if (builtin) ModificationTracker.NEVER_CHANGED else this.parent)
  }
}

private fun collectBlockComments(nonSolElements: List<PsiElement>): List<PsiElement> {
  val fromEnd = nonSolElements.dropWhile { it.elementType != SolidityTokenTypes.COMMENT || !it.text.contains("*/") }
  val start = fromEnd.indexOfFirst { it.elementType == SolidityTokenTypes.COMMENT && it.text.startsWith("/**") }
  return if (start >= 0) fromEnd.subList(0, start + 1) else emptyList()
}

class SolDocumentationProvider : AbstractDocumentationProvider() {
  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    val definition = element?.let { calcDefinition(it) } ?: return null
    return StringBuilder().appendDefinition(definition).toString()
  }

  private val keywordColors = (SolHighlighter.keywords.types + SolHighlighter.types.types).filter { it != SolidityTokenTypes.RETURN }.map { it.toString() }
    .plus(setOf("u?int(\\d+)", "u?fixed(\\d+)", "bytes?(\\d+)", "error"))
    .joinToString("|", "\\b(", ")\\b").toRegex()
  private val col = SolColor.TYPE.textAttributesKey.defaultAttributes.foregroundColor
  private val typeRGB = "rgb(${col.red},${col.green},${col.blue})"
  private fun String.colorizeKeywords(): String {
    return this.replace(keywordColors) { it.value.colorizeKeyword() }
  }
  private fun String.colorizeKeyword()  = "<b style='color:$typeRGB'>${this}</b>"


  /** Yul built-ins resolve to nothing: the call itself carries their documentation. */
  override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
    contextElement?.let { yulBuiltinCall(it) }

  override fun generateDoc(psiElement: PsiElement, originalElement: PsiElement?): String? {
    (psiElement as? SolYulFunctionCall ?: originalElement?.let { yulBuiltinCall(it) })?.let { call ->
      YulBuiltins[call.firstChild.text]?.let { return yulBuiltinDoc(it) }
    }
    // psiElement is the resolved declaration and originalElement the token at the caret, usually far from it;
    // only a member access the reference couldn't resolve to a describable declaration is resolved here
    var element = psiElement
    var definition = calcDefinition(element)
    if (definition == null) {
      val access = element as? SolMemberAccessExpression ?: originalElement?.parent as? SolMemberAccessExpression
      element = access?.let { SolResolver.resolveMemberAccess(it).filterIsInstance<SolFunctionDefinition>().firstOrNull() } ?: return null
      definition = calcDefinition(element) ?: return null
    }
    val builder = StringBuilder().appendDefinition(definition)
    val own: List<PsiElement> = element.comments()
    val inherited: List<PsiElement> = if (own.isEmpty() || own.any { it.isInheritDoc() }) {
      when (element) {
        is SolParameterDef -> collectInheritanceComments(element) + collectParameterComments(element)
        is SolFunctionDefinition -> collectInheritanceComments(element)
        is SolStateVariableDeclaration -> collectInheritanceComments(element)
        else -> emptyList()
      }
    } else emptyList()
    if (own.isNotEmpty() || inherited.isNotEmpty()) {
      val natSpec = NatSpecDoc.parse(own) + NatSpecDoc.parse(inherited)
      val html = if (element is SolParameterDef) natSpec.parameterHtml(element.project, element.identifier?.text) else natSpec.toHtml(element.project)
      builder.append(html ?: "")
    }
    return builder.toString()
  }

  private fun yulBuiltinCall(element: PsiElement): SolYulFunctionCall? {
    val call = element as? SolYulFunctionCall ?: (element.parent as? SolYulFunctionCall)?.takeIf { it.firstChild == element }
    return call?.takeIf { YulBuiltins[it.firstChild.text] != null && it.reference?.resolve() == null }
  }

  private fun yulBuiltinDoc(builtin: YulBuiltins.Builtin): String = buildString {
    append(DEFINITION_START)
    append(builtin.name.colorizeKeyword()).append(builtin.signature)
    if (builtin.returns) append(" -&gt; v")
    append(DEFINITION_END)
    append(CONTENT_START).append(StringUtil.escapeXmlEntities(builtin.description)).append(CONTENT_END)
    append(SECTIONS_START)
    val evm = when {
      builtin.since != null -> "since ${builtin.since.displayName}"
      else -> "all"
    } + (builtin.until?.let { ", removed in ${it.displayName}" } ?: "")
    append(SECTION_HEADER_START).append("EVM version:").append(SECTION_SEPARATOR).append(evm).append(SECTION_END)
    if (builtin.deprecated) {
      append(SECTION_HEADER_START).append("Deprecated:").append(SECTION_SEPARATOR).append("yes").append(SECTION_END)
    }
    append(SECTIONS_END)
  }

  private fun collectInheritanceComments(element: SolElement): List<PsiElement> {
    if (element !is SolNamedElement) return emptyList()
    val function = element.parentOfType<SolFunctionDefElement>(false)
      ?: element.parentOfType<SolStateVariableDeclaration>(false)?.let { SolPsiFactory(element.project).createFunction("function ${element.name}() {}") }
      ?: return emptyList()

    fun findTargetElement(base: SolFunctionDefinition) =  (when (element) {
          is SolFunctionDefinition -> base.comments()
          is SolParameterDef -> base.parameters.find { it.identifier?.text == element.identifier?.text }?.let { collectParameterComments(it) }
          is SolStateVariableDeclaration -> base.comments()
          else -> null
        }) ?: emptyList()

    element.comments().find { it.isInheritDoc() }?.let {
      it.siblings().firstOrNull { it.elementType == SolidityTokenTypes.COMMENT }?.let {
        val ref = it.text.trimStart().split("\\s".toRegex(), limit = 2)[0]
        SolFunctionResolver.collectOverridden(function, element.parentOfType(false)).find { it.contract?.identifier?.text == ref }?.let {
          return findTargetElement(it)
        }
      }
    }

    val base = SolFunctionResolver.collectOverridden(function).takeIf { it.size == 1 }?.first() ?: return emptyList()
    if (function.parameters.mapNotNull { it.identifier?.text } != base.parameters.mapNotNull { it.identifier?.text }) return emptyList()
    return findTargetElement(base)
  }

  private fun collectParameterComments(element: SolParameterDef): List<PsiElement> {
    val functionDefinition = element.parentOfType<SolFunctionDefElement>() ?: return emptyList()
    val comments = functionDefinition.comments().takeIf { it.isNotEmpty() } ?: return emptyList()
    val natIndexes = comments.withIndex().filter { it.value.elementType == SolidityTokenTypes.NAT_SPEC_TAG }

    val returnParam = functionDefinition.returns?.parameterDefList?.contains(element) == true
    val natName = if (returnParam) "@return" else "@param"
    val singleReturn = returnParam && natIndexes.count { it.value.text == "@return" } == 1
    val paramName = element.identifier?.text ?: if (!singleReturn) return emptyList() else ""

    val natParam = natIndexes.find { it.value.text == natName && (singleReturn || comments.getOrNull(it.index + 1)?.text?.trimStart()?.startsWith(paramName) ?: false) } ?: return emptyList()
    val nextNatParam = natIndexes.getOrNull(natIndexes.indexOf(natParam) + 1)?.index ?: comments.size
    return comments.subList(natParam.index, nextNatParam)
  }

  private fun StringBuilder.appendDefinition(definition: String): StringBuilder =
    append(DEFINITION_START).append(definition.colorizeKeywords()).append(DEFINITION_END)

  private fun calcDefinition(element: PsiElement): String? {
    return when (element) {
      is SolContractDefinition -> element.doc()
      is SolStructDefinition ->  "struct " + element.identifier.idName()
      is SolFunctionDefinition -> element.doc()
      is SolConstructorDefinition -> element.doc()
      is SolParameterDef -> element.colorizedTypeText()
      is SolVariableDeclaration -> element.colorizedTypeText()
      is SolTypedDeclarationItem -> "${getSolType(element.typeName).toString().colorizeKeywords()} ${element.identifier.idName()}"
      is SolStateVariableDeclaration -> "${getSolType(element.typeName).toString().colorizeKeywords()} ${element.identifier.idName()}"
      is SolEnumDefinition -> element.doc()
      is SolEventDefinition -> element.doc()
      is SolErrorDefinition -> element.doc()
      is SolModifierDefinition -> element.doc()
      is SolUserDefinedValueTypeDefinition -> element.doc()
      is SolEnumValue -> element.idName()
      else -> null
    }
  }

  private fun PsiElement?.idName() = this?.text ?: "<no_name>"

  private val colorizedTypes = TokenSet.orSet(SolHighlighter.types, TokenSet.create(SolidityTokenTypes.CONTRACT_DEFINITION, SolidityTokenTypes.STRUCT))
  private fun PsiElement.colorizedTypeText() = descendantsOfType<LeafPsiElement>().joinToString("") { el -> el.text.let { if (el.elementType in colorizedTypes || el.parent.elementType == SolidityTokenTypes.USER_DEFINED_TYPE_NAME) it.colorizeKeyword() else it } }

  private fun List<PsiElement>?.doc(separator: String = ", ", prefix: String = "", postfix : String = ""): String {
    return takeIf { it?.isNotEmpty() ?: false }
      ?.joinToString(separator, prefix, postfix) { e ->
        if (e is SolParameterDef && e.identifier?.text == SolInternalTypeFactory.varargsId) varargsText(getSolType(e.typeName))
        else e.colorizedTypeText()
      } ?: ""
  }
  private fun SolContractDefinition.doc() : String {
    return "${contractType.docName} ${identifier.idName()}" +
      inheritanceSpecifierList.doc(prefix = " is ")
  }

  private fun SolFunctionDefinition.doc() : String {
    return ("${if (isConstructor) "constructor" else "function"} ${identifier.idName()}(${parameters.doc()}) ${functionVisibilitySpecifierList.doc(" ")} " +
      "${stateMutabilitySpecifierList.doc(" ")} ${modifierInvocationList.doc(" ")} " +
      (returns?.parameterDefList?.doc(", ", "returns (", ")") ?: "")).replace("  ", " ")
  }

  private fun SolConstructorDefinition.doc() : String {
    return ("constructor ${findContract()?.identifier.idName()}(${parameterList?.parameterDefList.doc()}) ${functionVisibilitySpecifierList.doc(" ")} " +
      "${stateMutabilitySpecifierList.doc(" ")} ${modifierInvocationList.doc(" ")} "
      ).replace("  ", " ")
  }


  private fun SolUserDefinedValueTypeDefinition.doc() : String {
    return "type ${identifier.idName()} is ${elementaryTypeName?.text}"
  }

  private fun SolEnumDefinition.doc() : String {
    return "enum ${identifier.idName()} { ${enumValueList.doc()} }"
  }

  private fun SolEventDefinition.doc() : String {
    return "event ${identifier.idName()} ${children.joinToString { it.text.colorizeKeywords() }}"
  }

  private fun SolErrorDefinition.doc() : String {
    return "error ${(this as? SolErrorDefMixin)?.nameIdentifier?.idName() ?: "N/A"} ${children.joinToString { it.text.colorizeKeywords() }}"
  }

  private fun SolModifierDefinition.doc() : String {
    return "modifier ${identifier.idName()}(${parameterList?.parameterDefList?.doc() ?: ""}) " +
      "${virtualSpecifierList.takeIf { it.isNotEmpty() }?.doc() ?: ""} ${overrideSpecifierList.takeIf { it.isNotEmpty() }?.doc() ?: ""}"
  }
}


fun PsiElement.isInheritDoc() = isNatSpecTag("@inheritdoc")
