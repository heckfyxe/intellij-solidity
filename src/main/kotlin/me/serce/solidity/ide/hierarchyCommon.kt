package me.serce.solidity.ide

import com.intellij.ide.hierarchy.HierarchyBrowserManager
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

// What the type and call hierarchies share.

/** The element a hierarchy opens on: found around the caret in an editor, else the selected element. */
internal fun hierarchyTarget(
  dataContext: DataContext,
  inEditor: (Editor, PsiFile) -> PsiElement?,
  selected: (PsiElement) -> PsiElement?,
): PsiElement? {
  val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
  val editor = CommonDataKeys.EDITOR.getData(dataContext)
  if (editor != null) {
    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
    return inEditor(editor, file)
  }
  return CommonDataKeys.PSI_ELEMENT.getData(dataContext)?.let(selected)
}

/** Alphabetical when the browser's sort button is on, otherwise the order the tree structure built. */
internal fun hierarchyComparator(project: Project): Comparator<NodeDescriptor<*>> {
  val state = HierarchyBrowserManager.getInstance(project).state
  return if (state != null && state.SORT_ALPHABETICALLY) AlphaComparator.getInstance() else Comparator { _, _ -> 0 }
}

/** A node showing the text [present] appends; the base node gets the base marker icon. */
abstract class SolHierarchyNodeDescriptor(
  project: Project, parent: HierarchyNodeDescriptor?, element: PsiElement, isBase: Boolean,
) : HierarchyNodeDescriptor(project, parent, element, isBase) {
  protected abstract fun present(element: PsiElement, text: CompositeAppearance.DequeEnd)

  override fun update(): Boolean {
    var changes = super.update()
    val element = psiElement ?: return invalidElement()
    if (changes && myIsBase) icon = getBaseMarkerIcon(icon)
    val old = myHighlightedText
    myHighlightedText = CompositeAppearance()
    present(element, myHighlightedText.ending)
    myName = myHighlightedText.text
    if (myHighlightedText != old) changes = true
    return changes
  }
}
