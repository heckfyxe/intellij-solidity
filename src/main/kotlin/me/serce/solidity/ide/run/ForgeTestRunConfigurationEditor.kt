package me.serce.solidity.ide.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ForgeTestRunConfigurationEditor(private val project: Project) : SettingsEditor<ForgeTestRunConfiguration>() {
  private lateinit var mainPanel: JPanel
  private lateinit var contractNameField: RawCommandLineEditor
  private lateinit var testNameField: RawCommandLineEditor
  private lateinit var workingDirectoryField: TextFieldWithBrowseButton
  private val profileField = JBTextField()
  private val fork = ForgeTargetEditor(project, "Fork URL or endpoint alias (--fork-url):")
  private val forkBlockField = JBTextField()
  private val fuzzSeedField = JBTextField()
  private val argumentsField = RawCommandLineEditor()

  init {
    createUI()
  }

  private fun createUI() {
    contractNameField = RawCommandLineEditor()
    testNameField = RawCommandLineEditor()
    workingDirectoryField = TextFieldWithBrowseButton().apply {
      addBrowseFolderListener(
        "Select Working Directory",
        "Select the working directory for Forge",
        null,
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
      )
    }

    mainPanel = FormBuilder.createFormBuilder()
      .addLabeledComponent("Test contract (leave empty for all contracts):", contractNameField)
      .addLabeledComponent("Test name (leave empty for all tests):", testNameField)
      .addLabeledComponent("Working directory:", workingDirectoryField)
      .addLabeledComponent("Profile (FOUNDRY_PROFILE):", profileField)
      .let(fork::addTo)
      .addLabeledComponent("Fork block (--fork-block-number, empty for the latest):", forkBlockField)
      .addLabeledComponent("Fuzz seed (--fuzz-seed):", fuzzSeedField)
      .addLabeledComponent("Additional arguments:", argumentsField)
      .addComponentFillVertically(JPanel(), 0)
      .panel
  }

  override fun resetEditorFrom(configuration: ForgeTestRunConfiguration) {
    contractNameField.text = configuration.contractName
    testNameField.text = configuration.testName
    workingDirectoryField.text = configuration.workingDirectory
    profileField.text = configuration.profile
    fork.reset(configuration.fork)
    forkBlockField.text = configuration.forkBlock
    fuzzSeedField.text = configuration.fuzzSeed
    argumentsField.text = configuration.additionalArguments
  }

  override fun applyEditorTo(configuration: ForgeTestRunConfiguration) {
    configuration.contractName = contractNameField.text
    configuration.testName = testNameField.text
    configuration.workingDirectory = workingDirectoryField.text
    configuration.profile = profileField.text
    fork.apply(configuration.fork)
    configuration.forkBlock = forkBlockField.text.trim()
    configuration.fuzzSeed = fuzzSeedField.text
    configuration.additionalArguments = argumentsField.text
  }

  override fun createEditor(): JComponent = mainPanel
}
