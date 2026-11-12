package me.serce.solidity.settings

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.dsl.builder.ButtonsGroup
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.layout.and
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JRadioButton
import kotlin.io.path.Path
import kotlin.reflect.KMutableProperty0

private const val HELP_TOPIC = "reference.settings.solidity"

class SolidityConfigurable(internal val project: Project) : BoundSearchableConfigurable(
  "Solidity", HELP_TOPIC, CONFIGURABLE_ID
) {

  override fun createPanel(): DialogPanel {
    val settings: SoliditySettings = SoliditySettings.getInstance(project)

    return panel {
      collapsibleGroup("Formatter") {
        lateinit var foundryFormatter: JRadioButton
        buttonsGroup {
          row {
            foundryFormatter = radioButton("forge fmt")
              .bindSelected(SelectedWhen(settings::formatterType, FormatterType.FOUNDRY)).component
            helpLabel(
              "Reformat Code runs forge fmt with the [fmt] settings of the nearest foundry.toml. " +
                "Typing only indents new lines; it never reformats code."
            )
          }
          row {
            radioButton("Disabled")
              .bindSelected(SelectedWhen(settings::formatterType, FormatterType.DISABLED))
            helpLabel("Leave Solidity files to another formatter, such as Prettier")
          }
        }
        val formatterManual = foundryConfiguration(
          settings::formatterConfigurationMode,
          settings::formatterFoundryExecutablePath,
          settings::formatterFoundryConfigPath,
        )
        formatterManual.modeGroup.visibleIf(foundryFormatter.selected)
        formatterManual.manualPanel.visibleIf(formatterManual.manualSelected.and(foundryFormatter.selected))
        row {
          link("Format on save…") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "actions.on.save")
          }
          comment("Enable Reformat code in Actions on Save")
        }.visibleIf(foundryFormatter.selected)
      }.expanded = true

      collapsibleGroup("Foundry Test Configuration") {
        val testManual = foundryConfiguration(
          settings::testFoundryConfigurationMode,
          settings::testFoundryExecutablePath,
          settings::testFoundryConfigPath,
        )
        testManual.manualPanel.visibleIf(testManual.manualSelected)
      }.expanded = true
    }
  }

  private class FoundryConfigurationUi(
    val modeGroup: ButtonsGroup,
    val manualSelected: ComponentPredicate,
    val manualPanel: Panel,
  )

  /** Automatic/manual radio buttons plus the forge executable and foundry.toml fields shown in manual mode. */
  private fun Panel.foundryConfiguration(
    mode: KMutableProperty0<ConfigurationMode>,
    executablePath: KMutableProperty0<String>,
    configPath: KMutableProperty0<String>,
  ): FoundryConfigurationUi {
    lateinit var manual: JRadioButton
    val modeGroup = buttonsGroup {
      row {
        radioButton("Automatic Foundry configuration").bindSelected(SelectedWhen(mode, ConfigurationMode.AUTOMATIC))
        helpLabel(
          ApplicationNamesInfo.getInstance().fullProductName + " will use the forge executable installed at " +
            "USER_HOME/.foundry/bin/forge and the foundry.toml configuration file located in the same " +
            "folder as the current file or any of its parent folders."
        )
      }
      row {
        manual = radioButton("Manual Foundry configuration").bindSelected(SelectedWhen(mode, ConfigurationMode.MANUAL)).component
      }
    }
    val manualPanel = panel {
      row("Forge executable") {
        textFieldWithBrowseButton("Forge executable") { it.path }.bindText(executablePath)
      }
      row("Path of foundry.toml") {
        textFieldWithBrowseButton("Path of foundry.toml", project) { it.path }
          .bindText(configPath)
          .validationOnInput(validateFoundryTomlConfigDir())
      }
    }
    return FoundryConfigurationUi(modeGroup, manual.selected, manualPanel)
  }

  private fun Row.helpLabel(text: String) {
    val label = ContextHelpLabel.create(text)
    label.border = JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP)
    cell(label)
  }

  /** A radio button binding that is selected while [property] equals [value] and sets it when chosen. */
  private class SelectedWhen<T>(
    private val property: KMutableProperty0<T>,
    private val value: T,
  ) : MutableProperty<Boolean> {
    override fun get(): Boolean = property.get() == value

    override fun set(value: Boolean) {
      if (value) {
        property.set(this.value)
      }
    }
  }

  private fun validateFoundryTomlConfigDir(): ValidationInfoBuilder.(TextFieldWithBrowseButton) -> ValidationInfo? = {
    val selected = VfsUtil.findFile(Path(it.text), true)
    if (selected == null || !selected.exists() || selected.name != "foundry.toml") {
      ValidationInfo("Failed to locate foundry.toml configuration file", it)
    } else {
      null
    }
  }

  companion object {
    const val CONFIGURABLE_ID = "Settings.Solidity"
  }
}
