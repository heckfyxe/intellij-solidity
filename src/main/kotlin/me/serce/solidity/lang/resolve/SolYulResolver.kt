package me.serce.solidity.lang.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.impl.SolYulFunctionDefMixin
import me.serce.solidity.lang.types.SolInternalTypeFactory
import me.serce.solidity.lang.types.findContract

/**
 * Name resolution inside inline assembly and Yul files, following Yul's scoping rules:
 * - a variable is visible after its declaration in its block and the blocks nested in it;
 * - a function is visible in the whole block it is defined in (also before its definition) and the nested blocks;
 * - a function body sees its parameters and return variables, but no variable declared outside of it;
 * - the declarations of a `for` loop's init block are visible in its condition, post block and body.
 *
 * A name that Yul doesn't declare refers to a Solidity variable visible at the assembly block (outside of Yul
 * functions only constants and state variables, which can only be used through `.slot`/`.offset`).
 */
object SolYulResolver {

  /** The declarations visible at [place], innermost first. */
  class Scope(
    val variables: List<SolYulVariable>,
    val functions: List<SolYulFunctionDefinition>,
    val solidity: List<SolNamedElement>,
    /** Labels of pre-0.5 inline assembly, visible in their whole block. */
    val labels: List<SolYulLabel> = emptyList(),
  )

  fun resolveVariable(path: SolReferenceElement): List<SolNamedElement> {
    val name = path.referenceName
    val scope = scope(path)
    scope.variables.firstOrNull { it.name == name }?.let { return listOf(it) }
    scope.labels.firstOrNull { it.name == name }?.let { return listOf(it) }
    return scope.solidity.filter { it.name == name }.findBest {
      when (it) {
        is SolVariableDeclaration, is SolDeclarationItem, is SolTypedDeclarationItem -> 1
        is SolParameterDef -> 10
        else -> 100
      }
    }
  }

  fun resolveFunction(call: SolReferenceElement): List<SolNamedElement> {
    val name = call.referenceName
    return scope(call, collectSolidity = false).functions.firstOrNull { it.name == name }?.let { listOf(it) } ?: emptyList()
  }

  fun scope(place: PsiElement, collectSolidity: Boolean = true): Scope {
    val variables = mutableListOf<SolYulVariable>()
    val functions = mutableListOf<SolYulFunctionDefinition>()
    val labels = mutableListOf<SolYulLabel>()
    val placeOffset = place.textRange.startOffset
    var insideFunction = false
    var prev: PsiElement = place
    var current: PsiElement? = place.parent
    var assembly: SolInlineAssemblyStatement? = null
    while (current != null && current !is PsiFile) {
      when (current) {
        is SolYulBlock -> {
          val statements = current.yulStatementList
          if (!insideFunction) {
            statements.asReversed()
              .filter { it.textRange.endOffset <= placeOffset }
              .forEach { st -> st.yulVariableDeclaration?.let { variables += it.yulVariableList.asReversed() } }
          }
          statements.mapNotNullTo(functions) { it.yulFunctionDefinition }
          if (!insideFunction) statements.mapNotNullTo(labels) { it.yulLabel }
        }

        is SolYulForStatement -> {
          val init = current.yulBlockList.firstOrNull()
          if (init != null && init != prev && !insideFunction) {
            init.yulStatementList.asReversed().forEach { st ->
              st.yulVariableDeclaration?.let { variables += it.yulVariableList.asReversed() }
            }
          }
        }

        is SolYulFunctionDefinition -> {
          // the parameters are only visible in the body, not in the signature itself
          if (prev is SolYulBlock && !insideFunction) {
            variables += current.yulVariableList
          }
          insideFunction = true
        }

        is SolInlineAssemblyStatement -> {
          assembly = current
          break
        }
      }
      prev = current
      current = current.parent
    }
    val solidity = if (collectSolidity && assembly != null) solidityDeclarations(assembly, insideFunction) else emptyList()
    return Scope(variables, functions, solidity, labels)
  }

  private fun solidityDeclarations(assembly: SolInlineAssemblyStatement, insideFunction: Boolean): List<SolNamedElement> {
    val global = SolInternalTypeFactory.of(assembly.project).globalType.ref
    return SolResolver.lexicalDeclarations(assembly)
      .filter {
        when (it) {
          is SolStateVariableDeclaration, is SolConstantVariable -> true
          is SolVariableDeclaration, is SolDeclarationItem, is SolTypedDeclarationItem, is SolParameterDef -> !insideFunction
          else -> false
        }
      }
      .filter { it.findContract() != global }
      .toList()
  }

  val SolYulFunctionDefinition.parameters: List<SolYulVariable>
    get() = (this as SolYulFunctionDefMixin).parameters
}
