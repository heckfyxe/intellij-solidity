package me.serce.solidity.settings

import com.intellij.openapi.components.BaseState
import com.intellij.util.xmlb.annotations.OptionTag

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
  // the formatter options are stored under the names they had before the test options existed
  @get:OptionTag("configurationMode")
  var formatterConfigurationMode by enum(ConfigurationMode.AUTOMATIC)
  @get:OptionTag("executablePath")
  var formatterFoundryExecutablePath by property("") { it.isEmpty() }
  @get:OptionTag("configPath")
  var formatterFoundryConfigPath by property("") { it.isEmpty() }

  var testFoundryConfigurationMode by enum(ConfigurationMode.AUTOMATIC)
  var testFoundryExecutablePath by property("") { it.isEmpty() }
  var testFoundryConfigPath by property("") { it.isEmpty() }
}
