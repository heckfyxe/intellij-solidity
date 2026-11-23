package me.serce.solidity.lang.psi

/**
 * Whether this expression needs parentheses to become the operand of a unary operator such as `!`, which binds
 * tighter than any binary operator. Names, literals, calls, member and index accesses and parenthesized
 * expressions don't.
 */
fun SolExpression.needsParenthesesAsOperand(): Boolean =
  this !is SolPrimaryExpression && this !is SolFunctionCallExpression && this !is SolMemberAccessExpression &&
    this !is SolIndexAccessExpression && this !is SolSeqExpression
