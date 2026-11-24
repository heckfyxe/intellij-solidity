package me.serce.solidity.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.xmlb.XmlSerializer

class SoliditySettingsStateTest : UsefulTestCase() {
  fun testFormatterOptionsKeepTheirStoredNames() {
    val state = SoliditySettingsState().apply {
      formatterConfigurationMode = ConfigurationMode.MANUAL
      formatterFoundryExecutablePath = "/bin/forge"
      formatterFoundryConfigPath = "/project"
    }
    val xml = JDOMUtil.write(XmlSerializer.serialize(state))
    assertTrue(xml, xml.contains("""<option name="configurationMode" value="MANUAL" />"""))
    assertTrue(xml, xml.contains("""<option name="executablePath" value="/bin/forge" />"""))
    assertTrue(xml, xml.contains("""<option name="configPath" value="/project" />"""))
  }

  fun testReadsSettingsSavedByOlderVersions() {
    val element = JDOMUtil.load(
      """<state><option name="configurationMode" value="MANUAL" /><option name="executablePath" value="/bin/forge" /></state>"""
    )
    val state = XmlSerializer.deserialize(element, SoliditySettingsState::class.java)
    assertEquals(ConfigurationMode.MANUAL, state.formatterConfigurationMode)
    assertEquals("/bin/forge", state.formatterFoundryExecutablePath)
    assertEquals("", state.testFoundryExecutablePath)
  }
}
