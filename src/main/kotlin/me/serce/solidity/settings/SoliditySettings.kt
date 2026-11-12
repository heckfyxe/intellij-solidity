package me.serce.solidity.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import java.io.File

@Service(Service.Level.PROJECT)
@State(name = "SoliditySettings", storages = [(Storage("solidity.xml"))])
class SoliditySettings :
  SimplePersistentStateComponent<SoliditySettingsState>(SoliditySettingsState()) {

  var formatterConfigurationMode: ConfigurationMode
    get() = state.configurationMode
    set(value) {
      state.configurationMode = value
    }

  var formatterType: FormatterType
    get() = state.formatterType.takeIf { it != FormatterType.INTELLIJ_SOLIDITY } ?: FormatterType.FOUNDRY
    set(value) {
      state.formatterType = value
    }

  var formatterFoundryExecutablePath: String
    get() = state.executablePath ?: ""
    set(value) {
      state.executablePath = value
    }

  var formatterFoundryConfigPath: String
    get() = state.configPath ?: ""
    set(value) {
      state.configPath = configDirectory(value)
    }

  var testFoundryConfigurationMode: ConfigurationMode
    get() = state.testFoundryConfigurationMode
    set(value) {
      state.testFoundryConfigurationMode = value
    }

  var testFoundryExecutablePath: String
    get() = state.testFoundryExecutablePath ?: ""
    set(value) {
      state.testFoundryExecutablePath = value
    }

  var testFoundryConfigPath: String
    get() = state.testFoundryConfigPath ?: ""
    set(value) {
      state.testFoundryConfigPath = configDirectory(value)
    }

  /** Users may pick foundry.toml itself; the stored setting is the directory that contains it. */
  private fun configDirectory(path: String): String {
    val file = File(path)
    return if (file.isFile) file.parentFile.path else path
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): SoliditySettings = project.service()
  }
}
