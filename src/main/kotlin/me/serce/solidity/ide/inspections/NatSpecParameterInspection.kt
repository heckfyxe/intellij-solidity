package me.serce.solidity.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import me.serce.solidity.ide.hints.comments
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*

/**
 * Checks the `@param` and `@return` tags of the NatSpec (`///` or `/** */`) directly before a function, constructor,
 * event, error, modifier or state variable: every `@param` must name a parameter (once), there can't be more
 * `@return` tags than return values, state variables take no `@param` and only public ones a single `@return`.
 */
class NatSpecParameterInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : SolVisitor() {
      override fun visitFunctionDefinition(o: SolFunctionDefinition) {
        check(o, o.parameters.map { it.identifier?.text }, o.returns?.parameterDefList?.size ?: 0, holder)
      }

      override fun visitConstructorDefinition(o: SolConstructorDefinition) {
        check(o, o.parameterList?.parameterDefList.orEmpty().map { it.identifier?.text }, 0, holder)
      }

      override fun visitModifierDefinition(o: SolModifierDefinition) {
        check(o, o.parameterList?.parameterDefList.orEmpty().map { it.identifier?.text }, 0, holder)
      }

      override fun visitEventDefinition(o: SolEventDefinition) {
        check(o, o.indexedParameterList?.indexedParamDefList.orEmpty().map { it.identifier?.text }, 0, holder)
      }

      override fun visitErrorDefinition(o: SolErrorDefinition) {
        check(o, o.indexedParameterList?.indexedParamDefList.orEmpty().map { it.identifier?.text }, 0, holder)
      }

      override fun visitStateVariableDeclaration(o: SolStateVariableDeclaration) {
        val isPublic = o.visibility == Visibility.PUBLIC
        check(o, null, if (isPublic) 1 else 0, holder, stateVariable = true)
      }
    }
  }

  /** [parameterNames] is null for declarations that take no `@param` tags at all. */
  private fun check(
    declaration: PsiElement,
    parameterNames: List<String?>?,
    returnCount: Int,
    holder: ProblemsHolder,
    stateVariable: Boolean = false
  ) {
    val comments = declaration.comments().sortedBy { it.textRange.startOffset }
    if (comments.isEmpty()) return
    val names = parameterNames.orEmpty().filterNotNull().toSet()
    val documented = mutableSetOf<String>()
    var returnTags = 0
    comments.forEachIndexed { index, element ->
      if (element.elementType != SolidityTokenTypes.NAT_SPEC_TAG) return@forEachIndexed
      when (element.text) {
        "@param" -> {
          if (parameterNames == null) {
            holder.registerProblem(element, "'@param' is not valid for state variables")
            return@forEachIndexed
          }
          val name = tagArgument(comments.getOrNull(index + 1)) ?: return@forEachIndexed
          when {
            name !in names -> holder.registerProblem(element, "No parameter named '$name'")
            !documented.add(name) -> holder.registerProblem(element, "Parameter '$name' is documented more than once")
          }
        }
        "@return" -> {
          returnTags++
          if (returnTags > returnCount) {
            val message = when {
              stateVariable && returnCount == 0 -> "'@return' is only valid for public state variables"
              stateVariable -> "A public state variable can have only one '@return' tag"
              returnCount == 0 -> "'@return' documents a return value, but there is none"
              else -> "More '@return' tags than return values ($returnCount)"
            }
            holder.registerProblem(element, message)
          }
        }
      }
    }
  }

  /** The first word of the comment text right after a tag: `/// @param amount the amount` → `amount`. */
  private fun tagArgument(next: PsiElement?): String? {
    if (next == null || next.elementType != SolidityTokenTypes.COMMENT) return null
    val text = next.text
    if (text.isEmpty() || !text[0].isWhitespace()) return null
    val firstLine = text.trimStart(' ', '\t')
    val word = firstLine.takeWhile { !it.isWhitespace() }
    return word.takeIf { it.isNotEmpty() && it.first().let { c -> c.isLetter() || c == '_' || c == '$' } }
  }
}
