package me.serce.solidity.ide

import com.intellij.psi.util.PsiTreeUtil
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolEventDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.utils.SolTestBase

class SolFindUsagesProviderTest : SolTestBase() {
  fun testTypeAndName() {
    val file = InlineFile("interface IToken { event Transfer(); function total() external; }").psiFile
    val provider = SolFindUsagesProvider()
    val contract = PsiTreeUtil.findChildOfType(file, SolContractDefinition::class.java)!!
    val event = PsiTreeUtil.findChildOfType(file, SolEventDefinition::class.java)!!
    val function = PsiTreeUtil.findChildOfType(file, SolFunctionDefinition::class.java)!!
    assertEquals("interface", provider.getType(contract))
    assertEquals("IToken", provider.getDescriptiveName(contract))
    assertEquals("event", provider.getType(event))
    assertEquals("Transfer", provider.getNodeText(event, false))
    assertEquals("function", provider.getType(function))
  }
}
