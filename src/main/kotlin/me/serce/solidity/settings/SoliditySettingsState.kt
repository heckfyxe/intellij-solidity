package me.serce.solidity.settings

import com.intellij.openapi.components.BaseState

enum class ConfigurationMode {
  AUTOMATIC,
  MANUAL
}

enum class FormatterType {
  /** forge fmt runs on Reformat Code (and on save, when Reformat code is enabled in Actions on Save). */
  FOUNDRY,
  DISABLED,
  /** The plugin's own formatter, which was removed; read from older settings and treated as [FOUNDRY]. */
  INTELLIJ_SOLIDITY,
}

class SoliditySettingsState : BaseState() {
  var formatterType by enum(FormatterType.FOUNDRY)
  var configurationMode by enum(ConfigurationMode.AUTOMATIC)
  var executablePath by string()
  var configPath by string()

  var testFoundryConfigurationMode by enum(ConfigurationMode.AUTOMATIC)
  var testFoundryExecutablePath by string()
  var testFoundryConfigPath by string()

}
