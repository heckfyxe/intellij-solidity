package me.serce.solidity.ide.formatting

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** A single leaf spanning the whole file: the formatter has no whitespace between blocks to change. */
private class UntouchedFileBlock(private val fileNode: ASTNode) : ASTBlock {
  override fun getNode(): ASTNode = fileNode
  override fun getTextRange(): TextRange = fileNode.textRange
  override fun getSubBlocks(): List<Block> = emptyList()
  override fun getWrap(): Wrap? = null
  override fun getIndent(): Indent = Indent.getNoneIndent()
  override fun getAlignment(): Alignment? = null
  override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
  override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(Indent.getNoneIndent(), null)
  override fun isIncomplete(): Boolean = false
  override fun isLeaf(): Boolean = true
}

/**
 * Solidity code is formatted by forge fmt ([SolidityExternalFormatter]); this model only computes indentation, so that
 * Enter, typing `}` and Auto-Indent Lines indent the way forge fmt would. Reformatting with it changes nothing.
 */
class SolidityFormattingModelBuilder : FormattingModelBuilder {
  override fun createModel(context: FormattingContext): FormattingModel {
    val element = context.psiElement
    val settings = context.codeStyleSettings
    val containingFile = element.containingFile
    if (context.formattingMode == FormattingMode.REFORMAT) {
      return FormattingModelProvider.createFormattingModelForPsiFile(containingFile, UntouchedFileBlock(containingFile.node), settings)
    }
    val solidityBlock = SolFormattingBlock(element.node, null, Indent.getNoneIndent(), null, settings, false, false)

    return FormattingModelProvider.createFormattingModelForPsiFile(containingFile, solidityBlock, settings)
  }

  override fun getRangeAffectingIndent(file: PsiFile, offset: Int, elementAtOffset: ASTNode): TextRange? {
    return null
  }
}
