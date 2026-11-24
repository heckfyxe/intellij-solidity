package me.serce.solidity.ide.formatting

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.FormatterUtil
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.TokenSet
import me.serce.solidity.lang.core.SolidityTokenTypes.*
import java.util.*

open class SolFormattingBlock(
  private val astNode: ASTNode,
  private val alignment: Alignment?,
  private val indent: Indent,
  private val wrap: Wrap?,
  private val codeStyleSettings: CodeStyleSettings,
  private val isEnforceChildIndent: Boolean,
  private val extra: Boolean
) : ASTBlock {
  private val nodeSubBlocks: List<Block> by lazy { buildSubBlocks().let { if (extra) it + SyntheticSolFormattingBlock(this) else it } }
  private val isNodeIncomplete: Boolean by lazy { FormatterUtil.isIncomplete(node) }
  private val doc: Document? by lazy { (astNode.psi)?.let { PsiDocumentManager.getInstance(it.project).getDocument(it.containingFile) } }

  override fun getSubBlocks(): List<Block> = nodeSubBlocks

  private fun buildSubBlocks(): List<Block> {
    val blocks = ArrayList<Block>()
    var child = astNode.firstChildNode
    while (child != null) {
      val childType = child.elementType
      if (child.textRange.length == 0) {
        child = child.treeNext
        continue
      }
      if (childType === TokenType.WHITE_SPACE) {
        child = child.treeNext
        continue
      }
      val e = buildSubBlock(child)
      blocks.add(e)
      child = child.treeNext
    }
    return Collections.unmodifiableList(blocks)
  }

  private fun buildSubBlock(child: ASTNode): Block {
    var enforceChildIndent = isEnforceChildIndent
    val childType = child.elementType
    val type = astNode.elementType
    val parent = astNode.treeParent
    val parentType = parent?.elementType
    val firstChild = astNode.firstChildNode
    val firstChildType = firstChild?.elementType
    val lastChild = astNode.lastChildNode // the expression on the right-hand side
    val lastChildIsComposite = lastChild is CompositeElement && lastChild != firstChild
    if (type == FUNCTION_INVOCATION || type == SEQ_EXPRESSION ||
      parent != null && type in binaryExpressionTypes &&
      doc?.let { it.getLineNumber(parent.startOffset) != it.getLineNumber(astNode.startOffset) } == true) {
      enforceChildIndent = false
    }
    val result = when {
      child is PsiComment && type in indentedCommentParents -> Indent.getNormalIndent()

      type == CONTRACT_DEFINITION && childType in contractParts -> Indent.getNormalIndent()

      // header parts after the parameters (visibility, modifiers, `returns (...)`) placed on their own lines
      type in functionLikeDefinitions && childType != BLOCK && childType != SEMICOLON &&
        generateSequence(child.treePrev) { it.treePrev }.any { it.elementType == PARAMETER_LIST } -> Indent.getNormalIndent()

      // inheritance specifiers
      type == CONTRACT_DEFINITION && childType == INHERITANCE_SPECIFIER -> Indent.getNormalIndent()

      // fields inside structs
      type == STRUCT_DEFINITION && childType == VARIABLE_DECLARATION -> Indent.getNormalIndent()

      // ternary expression
      childType == TERNARY_EXPRESSION -> Indent.getNormalIndent()
      type == TERNARY_EXPRESSION -> if (firstChild == child) Indent.getNoneIndent() else Indent.getNormalIndent()


      // inside a block, list of parameters, etc..
      parentType in listLikeParents -> Indent.getNormalIndent()

      // all expressions inside parens should have indentation when lines are split
      parentType in controlStatements && childType != BLOCK -> {
        Indent.getNormalIndent()
      }

      // pasted code inside a block
      type == BLOCK && childType == IDENTIFIER -> Indent.getNormalIndent()

      // chained function calls: if the callee is another call, indent subsequent parts
      type == FUNCTION_CALL_EXPRESSION && firstChildType == FUNCTION_CALL_EXPRESSION && child != firstChild -> {
        Indent.getContinuationIndent()
      }

      // all function calls
      parentType in callParents -> Indent.getNormalIndent()

      // multi-line assign expression & return expressions
      type in assignmentLike && lastChildIsComposite && lastChild == child -> {
        enforceChildIndent = true
        Indent.getNormalIndent()
      }

      type == FUNCTION_INVOCATION && parent?.treeParent?.elementType == MAP_EXPRESSION_CLAUSE && childType in callArgumentParts -> Indent.getNormalIndent()

      else -> if (enforceChildIndent) Indent.getNormalIndent() else Indent.getNoneIndent()
    }
    val extra = parentType == TERNARY_EXPRESSION && (childType == QUESTION || childType == COLON) && parent?.treeParent?.elementType != VARIABLE_DEFINITION
    return SolFormattingBlock(child, alignment, result, null, codeStyleSettings, enforceChildIndent, extra)
  }

  private fun newChildIndent(childIndex: Int): Indent? {
    val type = node.elementType
    val previous = (subBlocks.getOrNull(childIndex - 1) as? ASTBlock)?.node
    val previousType = previous?.elementType
    return when {
      type == BLOCK && previous != null && previous.firstChildNode == null && previous !is PsiComment &&
        previousType !in statementDelimiters -> {
        // an unfinished statement (`uint a =`, `foo(a,`): the parser leaves its tokens loose in the block,
        // so continue it one level deeper than the statements around it
        doubleIndent()
      }

      type in bracedBodies -> {
        val lbraceIndex = subBlocks.indexOfFirst { it is ASTBlock && it.node?.elementType == LBRACE }
        if (lbraceIndex != -1 && lbraceIndex < childIndex) {
          Indent.getNormalIndent()
        } else {
          Indent.getNoneIndent()
        }
      }

      // the keyword and braces of an unchecked block are already indented one level deeper than the block itself
      type == UNCHECKED_BLOCK -> doubleIndent()

      // the body of `if (x)`, `else`, `while (x)`, `for (...)` and `do` written without braces
      type in controlStatements && previousType in bracelessBodyPredecessors -> Indent.getNormalIndent()

      // modifiers, visibility and `returns` moved to their own lines in a function header
      type in functionLikeDefinitions && childIndex > 0 && previousType != BLOCK -> {
        val parametersIndex = subBlocks.indexOfFirst { it is ASTBlock && it.node?.elementType == PARAMETER_LIST }
        if (parametersIndex != -1 && parametersIndex < childIndex) Indent.getNormalIndent() else Indent.getNoneIndent()
      }

      // inside a function call
      type == STATEMENT -> Indent.getContinuationIndent()
      type == FUNCTION_CALL_ARGUMENTS || type == FUNCTION_INVOCATION -> Indent.getNormalIndent()

      // lists and expressions continued on the next line; `uint a =` or `a =` followed by Enter
      type in continuedLists -> Indent.getNormalIndent()
      else -> Indent.getNoneIndent()
    }
  }

  private fun doubleIndent(): Indent {
    val indentSize = codeStyleSettings.getIndentOptions(node.psi?.containingFile?.fileType).INDENT_SIZE
    return Indent.getSpaceIndent(indentSize * 2)
  }

  override fun getNode(): ASTNode = astNode
  override fun getTextRange(): TextRange = astNode.textRange
  override fun getWrap(): Wrap? = wrap
  override fun getIndent(): Indent? = indent
  override fun getAlignment(): Alignment? = alignment

  // never used for reformatting (see SolidityFormattingModelBuilder), so whitespace is always kept as typed
  override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

  override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
    ChildAttributes(newChildIndent(newChildIndex), null)

  override fun isIncomplete(): Boolean = isNodeIncomplete

  override fun isLeaf(): Boolean = astNode.firstChildNode == null
}

class SyntheticSolFormattingBlock(real: SolFormattingBlock) : Block by real {
  override fun getSubBlocks(): List<Block> = emptyList()
}

// computed once: the blocks are built on every Enter, `}` and Auto-Indent
private val functionLikeDefinitions = TokenSet.create(FUNCTION_DEFINITION, CONSTRUCTOR_DEFINITION, MODIFIER_DEFINITION)
private val binaryExpressionTypes = TokenSet.create(OR_EXPRESSION, AND_EXPRESSION, EQ_EXPRESSION, COMP_EXPRESSION, OR_OP_EXPRESSION,
  XOR_OP_EXPRESSION, AND_OP_EXPRESSION, SHIFT_EXPRESSION, PLUS_MIN_EXPRESSION, MULT_DIV_EXPRESSION, EXPONENT_EXPRESSION)
private val indentedCommentParents = TokenSet.create(CONTRACT_DEFINITION, BLOCK, ENUM_DEFINITION, FUNCTION_DEFINITION,
  CONSTRUCTOR_DEFINITION, IF_STATEMENT, STRUCT_DEFINITION, YUL_BLOCK)
/** The members of a contract body (`ContractPart` is a private rule, so it has no element type). */
private val contractParts = TokenSet.create(STATE_VARIABLE_DECLARATION, USING_FOR_DECLARATION, STRUCT_DEFINITION,
  MODIFIER_DEFINITION, FUNCTION_DEFINITION, CONSTRUCTOR_DEFINITION, EVENT_DEFINITION, ERROR_DEFINITION, ENUM_DEFINITION,
  USER_DEFINED_VALUE_TYPE_DEFINITION)
private val listLikeParents = TokenSet.create(BLOCK, UNCHECKED_BLOCK, ENUM_DEFINITION, YUL_BLOCK, PARAMETER_LIST,
  INDEXED_PARAMETER_LIST, MAP_EXPRESSION, SEQ_EXPRESSION, INLINE_ARRAY_EXPRESSION, TYPED_DECLARATION_LIST, RETURN_ST)
private val controlStatements = TokenSet.create(IF_STATEMENT, WHILE_STATEMENT, DO_WHILE_STATEMENT, FOR_STATEMENT)
private val callParents = TokenSet.create(FUNCTION_INVOCATION, YUL_FUNCTION_CALL)
private val assignmentLike = TokenSet.create(VARIABLE_DEFINITION, ASSIGNMENT_EXPRESSION, RETURN_ST)
private val callArgumentParts = TokenSet.create(FUNCTION_CALL_ARGUMENTS, LPAREN, RPAREN)
private val statementDelimiters = TokenSet.create(LBRACE, RBRACE, SEMICOLON)
private val bracedBodies = TokenSet.create(BLOCK, YUL_BLOCK, CONTRACT_DEFINITION, STRUCT_DEFINITION, ENUM_DEFINITION)
private val bracelessBodyPredecessors = TokenSet.create(RPAREN, ELSE, DO)
private val continuedLists = TokenSet.create(MAP_EXPRESSION, PARAMETER_LIST, INDEXED_PARAMETER_LIST, INLINE_ARRAY_EXPRESSION,
  TERNARY_EXPRESSION, VARIABLE_DEFINITION, ASSIGNMENT_EXPRESSION)
