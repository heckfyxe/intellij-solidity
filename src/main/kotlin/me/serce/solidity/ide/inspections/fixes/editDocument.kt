package me.serce.solidity.ide.inspections.fixes

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Edits the document of [element]'s file and commits it. Quick fixes edit text, not PSI, with exact spacing: nothing
 * reformats it afterwards (Reformat Code is `forge fmt`).
 */
internal fun editDocument(element: PsiElement, action: (Document) -> Unit) {
  val documentManager = PsiDocumentManager.getInstance(element.project)
  val document = documentManager.getDocument(element.containingFile) ?: return
  documentManager.doPostponedOperationsAndUnblockDocument(document)
  action(document)
  documentManager.commitDocument(document)
}
