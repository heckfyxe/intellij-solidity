package me.serce.solidity.ide.actions

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.psi.PsiFile
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDirectory
import me.serce.solidity.ide.SolidityIcons

private const val CAPTION = "New Solidity File"

const val SMART_CONTRACT_TEMPLATE = "Solidity Contract"
const val FORGE_TEST_TEMPLATE = "Forge Test"
const val FORGE_SCRIPT_TEMPLATE = "Forge Script"

/**
 * forge's naming: `Counter` gives `Counter.t.sol` with `CounterTest` (`Counter.s.sol` with `CounterScript`); typing the
 * contract name (`CounterTest`) or the file name (`Counter.t.sol`) gives the same.
 */
internal fun forgeFileNames(input: String, fileSuffix: String, contractSuffix: String): Pair<String, String> {
  val base = input.trim().removeSuffix(".sol").removeSuffix(fileSuffix)
  val stem = base.removeSuffix(contractSuffix).ifEmpty { base }
  return "$stem$fileSuffix" to "$stem$contractSuffix"
}

class SolCreateFileAction : CreateFileFromTemplateAction(CAPTION, "", SolidityIcons.FILE_ICON), DumbAware {

  override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?) = CAPTION

  override fun buildDialog(
    project: Project,
    directory: PsiDirectory,
    builder: CreateFileFromTemplateDialog.Builder
  ) {
    builder.setTitle(CAPTION)
      .addKind("Smart contract", SolidityIcons.FILE_ICON, SMART_CONTRACT_TEMPLATE)
      .addKind("Smart contract interface", SolidityIcons.FILE_ICON, "Solidity Contract Interface")
      .addKind("Solidity library", SolidityIcons.FILE_ICON, "Solidity Library")
      .addKind("Forge test", SolidityIcons.FILE_ICON, FORGE_TEST_TEMPLATE)
      .addKind("Forge script", SolidityIcons.FILE_ICON, FORGE_SCRIPT_TEMPLATE)
      .setValidator(object : InputValidatorEx {
        override fun checkInput(inputString: String): Boolean {
          return getErrorText(inputString) == null
        }

        override fun canClose(inputString: String): Boolean {
          return getErrorText(inputString) == null
        }

        override fun getErrorText(inputString: String): String? {
          return if (!StringUtil.isEmpty(inputString) && FileUtil.sanitizeFileName(inputString, false) == inputString)
            null
          else "'$inputString' is not a valid Solidity module name"
        }
      })
  }

  override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? {
    val (fileName, contract) = when (template.name) {
      FORGE_TEST_TEMPLATE -> forgeFileNames(name, ".t", "Test")
      FORGE_SCRIPT_TEMPLATE -> forgeFileNames(name, ".s", "Script")
      else -> return super.createFileFromTemplate(name, template, dir)
    }
    // the template's extension is appended: `Counter.t` becomes `Counter.t.sol`
    return createFileFromTemplate(fileName, template, dir, defaultTemplateProperty, true, emptyMap(), mapOf("CONTRACT_NAME" to contract))
  }
}
