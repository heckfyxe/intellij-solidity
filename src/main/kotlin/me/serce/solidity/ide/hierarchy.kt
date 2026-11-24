package me.serce.solidity.ide

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.hierarchy.*
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.util.ArrayUtilRt
import me.serce.solidity.ide.navigation.findAllImplementations
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.findContract
import me.serce.solidity.lang.types.SolContract
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTree

class SolHierarchyTypeProvider : HierarchyProvider {
  override fun getTarget(dataContext: DataContext): PsiElement? = hierarchyTarget(dataContext, { editor, file ->
    val targetElement = TargetElementUtil.findTargetElement(
      editor,
      TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.LOOKUP_ITEM_ACCEPTED
    )
    targetElement as? SolContractDefinition
      ?: file.findElementAt(editor.caretModel.offset)?.findContract()
  }, { it as? SolContractDefinition })

  override fun createHierarchyBrowser(p0: PsiElement): HierarchyBrowser {
    return SolTypeHierarchyBrowser(p0 as SolContractDefinition)
  }

  override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
    (hierarchyBrowser as TypeHierarchyBrowserBase).changeView(TypeHierarchyBrowserBase.getTypeHierarchyType())
  }

}

class SolTypeHierarchyBrowser(element: SolContractDefinition) : TypeHierarchyBrowserBase(element.project, element) {
  override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? {
    return (descriptor as? TypeHierarchyNodeDescriptor)?.psiElement
  }

  override fun createTrees(trees: MutableMap<in String, in JTree>) {
    createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)
  }

  override fun createLegendPanel(): JPanel? = null

  override fun isApplicableElement(p0: PsiElement): Boolean = p0 is SolContractDefinition

  override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure? {
    val contract = psiElement as SolContractDefinition
    return when (typeName) {
      getSupertypesHierarchyType() -> SupertypesHierarchyTreeStructure(contract)
      getSubtypesHierarchyType() -> SubtypesHierarchyTreeStructure(contract, currentScopeType)
      getTypeHierarchyType() -> TypeHierarchyTreeStructure(contract, currentScopeType)
      else -> {
        thisLogger().error("unexpected type: $typeName")
        null
      }
    }
  }

  override fun getComparator(): Comparator<NodeDescriptor<*>> = hierarchyComparator(myProject)

  // the browser opens on the type hierarchy (supertypes and subtypes) of interfaces too
  override fun isInterface(p0: PsiElement): Boolean = false

  override fun canBeDeleted(p0: PsiElement?): Boolean = true

  override fun getQualifiedName(p0: PsiElement?): String {
    return (p0 as? SolContractDefinition)?.name ?: ""
  }

}

class SupertypesHierarchyTreeStructure(contract: SolContractDefinition) : HierarchyTreeStructure(contract.project, TypeHierarchyNodeDescriptor(contract.project, null, contract, true)) {
  override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
    val element = descriptor.psiElement as? SolContractDefinition
      ?: return ArrayUtilRt.EMPTY_OBJECT_ARRAY
    return SolContract(element).linearizeParents().map { TypeHierarchyNodeDescriptor(myProject, descriptor, it.ref, false) }.toTypedArray()
  }

}

open class SubtypesHierarchyTreeStructure : HierarchyTreeStructure {
  val currentScopeType: String

  constructor(contract: SolContractDefinition, currentScopeType: String) : super(contract.project, TypeHierarchyNodeDescriptor(contract.project, null, contract, true)) {
    this.currentScopeType = currentScopeType
  }

  constructor(descriptor: HierarchyNodeDescriptor, currentScopeType: String) : super(descriptor.project, descriptor) {
    this.currentScopeType = currentScopeType
  }

  override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
    val element = descriptor.psiElement as? SolContractDefinition ?: return ArrayUtilRt.EMPTY_OBJECT_ARRAY
    return element.findAllImplementations().map { TypeHierarchyNodeDescriptor(myProject, descriptor, it, false) }.toTypedArray()
  }
}

class TypeHierarchyTreeStructure(element: SolContractDefinition, currentScopeType: String)
  : SubtypesHierarchyTreeStructure(createHDescriptor(element), currentScopeType) {
  init {
    setBaseElement(myBaseDescriptor) //to set myRoot
  }

  companion object {
    fun createHDescriptor(element: SolContractDefinition): HierarchyNodeDescriptor {
      var descriptor: HierarchyNodeDescriptor? = null
      val superClasses = SolContract(element).linearizeParents().asReversed()
      for (superClass in superClasses) {
        val newDescriptor = TypeHierarchyNodeDescriptor(element.project, descriptor, superClass.ref, false)
        descriptor?.cachedChildren = arrayOf(newDescriptor)
        descriptor = newDescriptor
      }
      val newDescriptor = TypeHierarchyNodeDescriptor(element.project, descriptor, element, true)
      descriptor?.cachedChildren = arrayOf(newDescriptor)
      return newDescriptor
    }
  }
}

class TypeHierarchyNodeDescriptor(project: Project, parentDescriptor: HierarchyNodeDescriptor?, contract: PsiElement, isBase: Boolean) :
  SolHierarchyNodeDescriptor(project, parentDescriptor, contract, isBase) {
  override fun present(element: PsiElement, text: CompositeAppearance.DequeEnd) {
    val attributes = myColor?.let { TextAttributes(it, null, null, null, Font.PLAIN) }
    (element as? SolContractDefinition)?.let { text.addText(it.name ?: "", attributes) }
  }
}

