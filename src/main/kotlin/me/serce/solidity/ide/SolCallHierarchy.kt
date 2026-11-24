package me.serce.solidity.ide

import com.intellij.psi.PsiNamedElement
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ArrayUtilRt
import me.serce.solidity.lang.psi.SolConstructorDefinition
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionCallExpression
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.SolModifierDefinition
import me.serce.solidity.lang.psi.SolModifierInvocation
import me.serce.solidity.lang.psi.SolMemberAccessExpression
import me.serce.solidity.lang.psi.SolReferenceElement
import me.serce.solidity.lang.psi.SolStateVariableDeclaration
import me.serce.solidity.ide.navigation.SolGotoSuperHandler
import me.serce.solidity.lang.resolve.function.SolFunctionResolver
import javax.swing.JTree

/** Functions, modifiers and constructors: what Call Hierarchy shows. */
object SolCallables {
  private val CALLABLE_TYPES = arrayOf(
    SolFunctionDefinition::class.java, SolModifierDefinition::class.java, SolConstructorDefinition::class.java,
  )

  fun isCallable(element: PsiElement?) = element is SolFunctionDefinition || element is SolModifierDefinition

  /** The function, modifier or constructor [element] is in; a state variable or contract for initializers. */
  fun containerOf(element: PsiElement): PsiElement? =
    PsiTreeUtil.getParentOfType(element, false, *CALLABLE_TYPES)
      ?: PsiTreeUtil.getParentOfType(element, false, SolStateVariableDeclaration::class.java)
      ?: PsiTreeUtil.getParentOfType(element, false, SolContractDefinition::class.java)

  /**
   * Where [callable] is called (or a modifier used), grouped by the definition the call is in, in source order.
   * Calls to a function or modifier it overrides count too, since they dispatch to it, except static ones
   * (`super.f()`, `Base.f()`), which always reach the base definition.
   */
  fun callers(callable: PsiElement): List<Pair<PsiElement, List<PsiElement>>> {
    val scope = GlobalSearchScope.projectScope(callable.project)
    val overridden = when (callable) {
      is SolFunctionDefinition -> SolFunctionResolver.collectOverridden(callable)
      is SolModifierDefinition -> SolGotoSuperHandler.superTargets(callable)
      else -> emptyList()
    }
    val direct = ReferencesSearch.search(callable, scope).findAll().map { it.element }
    val dispatched = overridden.flatMap { base ->
      ReferencesSearch.search(base, scope).findAll().map { it.element }.filter { !isStaticCall(it) }
    }
    val references = (direct + dispatched).distinct()
      .sortedWith(compareBy({ it.containingFile?.name }, { it.textOffset }))
    return references.mapNotNull { ref -> containerOf(ref)?.let { it to ref } }
      .groupBy({ it.first }, { it.second }).toList()
  }

  /** The functions and modifiers [callable] calls or applies, grouped by callee, in source order. */
  fun callees(callable: PsiElement): List<Pair<PsiElement, List<PsiElement>>> {
    val calls = mutableListOf<Pair<PsiElement, PsiElement>>()
    val modifiers = when (callable) {
      is SolFunctionDefinition -> callable.modifierInvocationList
      is SolConstructorDefinition -> callable.modifierInvocationList
      else -> emptyList()
    }
    modifiers.forEach { invocation -> resolveCallable(invocation)?.let { calls += it to invocation } }
    PsiTreeUtil.findChildrenOfType(callable, SolFunctionCallExpression::class.java)
      .sortedBy { it.textOffset }
      .forEach { call -> resolveCallable(call)?.let { calls += it to call } }
    return calls.groupBy({ it.first }, { it.second }).toList()
  }

  /** `super.f()` or `Base.f()`: a call that names the contract whose definition it reaches. */
  private fun isStaticCall(reference: PsiElement): Boolean {
    val call = reference as? SolFunctionCallExpression ?: reference.parent as? SolFunctionCallExpression
    val callee = (call?.expression ?: reference) as? SolMemberAccessExpression ?: return false
    val qualifier = callee.expression
    if (qualifier.text == "super") return true
    val leaf = PsiTreeUtil.findChildrenOfType(qualifier, SolReferenceElement::class.java).lastOrNull()
      ?: qualifier as? SolReferenceElement
    return leaf?.reference?.resolve() is SolContractDefinition
  }

  private fun resolveCallable(call: PsiElement): PsiElement? {
    val target = when (call) {
      is SolModifierInvocation -> call.reference?.resolve()
      is SolFunctionCallExpression -> call.reference?.resolve()
      else -> null
    }
    return target?.takeIf { isCallable(it) }
  }
}

class SolCallHierarchyProvider : HierarchyProvider {
  override fun getTarget(dataContext: DataContext): PsiElement? = hierarchyTarget(dataContext, { editor, file ->
    file.findElementAt(editor.caretModel.offset)?.let { at ->
      // a call at the caret: its callee, otherwise the enclosing function
      val call = PsiTreeUtil.getParentOfType(at, SolFunctionCallExpression::class.java, SolModifierInvocation::class.java)
      call?.reference?.resolve()?.takeIf { SolCallables.isCallable(it) }
        ?: PsiTreeUtil.getParentOfType(at, false, SolFunctionDefinition::class.java, SolModifierDefinition::class.java)
    }
  }, { it.takeIf { SolCallables.isCallable(it) } })

  override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = SolCallHierarchyBrowser(target.project, target)

  override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
    (hierarchyBrowser as SolCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
  }
}

class SolCallHierarchyBrowser(project: Project, method: PsiElement) : CallHierarchyBrowserBase(project, method) {
  override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

  override fun createTrees(trees: MutableMap<in String, in JTree>) {
    trees[getCallerType()] = createTree(false).also { configureTree(it) }
    trees[getCalleeType()] = createTree(false).also { configureTree(it) }
  }

  private fun configureTree(tree: JTree) {
    PopupHandler.installPopupMenu(tree, IdeActions.GROUP_CALL_HIERARCHY_POPUP, ActionPlaces.CALL_HIERARCHY_VIEW_POPUP)
  }

  override fun isApplicableElement(element: PsiElement) = SolCallables.isCallable(element)

  override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? = when (type) {
    getCallerType() -> SolCallHierarchyTreeStructure(myProject, psiElement, callers = true)
    getCalleeType() -> SolCallHierarchyTreeStructure(myProject, psiElement, callers = false)
    else -> null
  }

  override fun getComparator(): Comparator<NodeDescriptor<*>> = hierarchyComparator(myProject)
}

class SolCallHierarchyTreeStructure(project: Project, element: PsiElement, private val callers: Boolean) :
  HierarchyTreeStructure(project, SolCallHierarchyNodeDescriptor(project, null, element, true, 0)) {
  override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
    val element = descriptor.psiElement ?: return ArrayUtilRt.EMPTY_OBJECT_ARRAY
    // a caller that isn't a function (a state variable initializer, a contract) has no callers of its own;
    // callees are functions and modifiers only
    if (callers && !SolCallables.isCallable(element)) return ArrayUtilRt.EMPTY_OBJECT_ARRAY
    // stop at recursion: a node whose element is already on the path
    var parent = descriptor.parentDescriptor as? HierarchyNodeDescriptor
    while (parent != null) {
      if (parent.psiElement == element) return ArrayUtilRt.EMPTY_OBJECT_ARRAY
      parent = parent.parentDescriptor as? HierarchyNodeDescriptor
    }
    val groups = if (callers) SolCallables.callers(element) else SolCallables.callees(element)
    return groups.map { (target, usages) -> SolCallHierarchyNodeDescriptor(myProject, descriptor, target, false, usages.size) }
      .toTypedArray()
  }
}

class SolCallHierarchyNodeDescriptor(
  project: Project,
  parent: HierarchyNodeDescriptor?,
  element: PsiElement,
  isBase: Boolean,
  private val usageCount: Int,
) : SolHierarchyNodeDescriptor(project, parent, element, isBase) {
  override fun present(element: PsiElement, text: CompositeAppearance.DequeEnd) {
    text.addText(presentableName(element))
    val contract = PsiTreeUtil.getParentOfType(element, SolContractDefinition::class.java)
    if (contract != null && contract != element) {
      text.addText("  ${contract.name}", SimpleTextAttributes.GRAY_ATTRIBUTES.toTextAttributes())
    }
    if (usageCount > 1) {
      text.addText("  ($usageCount usages)", SimpleTextAttributes.GRAY_ATTRIBUTES.toTextAttributes())
    }
  }

  companion object {
    fun presentableName(element: PsiElement): String = when (element) {
      is SolFunctionDefinition -> (element.name ?: element.firstChild.text) +
        element.parameters.joinToString(", ", "(", ")") { it.typeName.text }
      is SolModifierDefinition -> "modifier ${element.name}"
      is SolConstructorDefinition -> "constructor"
      else -> (element as? PsiNamedElement)?.name ?: "?"
    }
  }
}
