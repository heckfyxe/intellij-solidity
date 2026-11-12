package me.serce.solidity.ide.annotation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import me.serce.solidity.ide.run.ForgeTestRunConfigurationProducer
import me.serce.solidity.ide.run.hasForgeTests
import me.serce.solidity.ide.run.isForgeTest
import me.serce.solidity.ide.run.isForgeTestFile
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import javax.swing.Icon

class ForgeTestLineMarkerProvider : LineMarkerProvider {
  private val testIcon: Icon = AllIcons.RunConfigurations.TestState.Run

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(
      elements: MutableList<out PsiElement>,
      result: MutableCollection<in LineMarkerInfo<*>>
  ) {
    if (elements.isEmpty()) return

    val file = elements[0].containingFile ?: return
    if (!isForgeTestFile(file)) return

    for (element in elements) {
      when (element) {
        is SolContractDefinition -> {
          if (!element.hasForgeTests()) continue
          element.identifier?.let { identifier ->
            result.add(
              createTestLineMarker(
                identifier,
                "Run Forge Tests in ${element.name}",
                element.name ?: "",
                null
              )
            )
          }
        }

        is SolFunctionDefinition -> {
          if (element.isForgeTest()) {
            element.identifier?.let { identifier ->
              result.add(
                createTestLineMarker(
                  identifier,
                  "Run Forge Test ${element.name}",
                  element.contract?.name ?: continue,
                  element.name
                )
              )
            }
          }
        }
      }
    }
  }

  private fun createTestLineMarker(
      element: PsiElement,
      tooltip: String,
      contract: String,
      test: String?
  ): LineMarkerInfo<PsiElement> {
    val testName = listOfNotNull(contract, test).joinToString(".")
    val producer = ForgeTestRunConfigurationProducer()
    return object : LineMarkerInfo<PsiElement>(
      element,
      element.textRange,
      testIcon,
      { tooltip },
      { _, el -> producer.runTest(el, contract, test) },
        GutterIconRenderer.Alignment.LEFT,
      { testName }
    ) {}
  }
}
