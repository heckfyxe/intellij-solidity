package me.serce.solidity.chain.compile

import junit.framework.TestCase
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.foundry.SolcLocator.Version

class SolcRequestTest : TestCase() {
  // trimmed `forge config --json` output of forge 1.9
  private val forgeConfigJson = """
    {"src":"src","test":"test","out":"out","libs":["lib"],"remappings":["forge-std/=lib/forge-std/src/"],
     "solc":null,"evm_version":"paris","via_ir":true,"optimizer":true,"optimizer_runs":1000,"optimizer_details":null}
  """.trimIndent()

  fun testParsesCompilerSettingsFromForgeConfig() {
    val config = FoundryProject.parseConfig(forgeConfigJson)
    assertEquals("paris", config.evmVersion)
    assertTrue(config.viaIr)
    assertTrue(config.optimizer)
    assertEquals(1000, config.optimizerRuns)
    assertNull(config.solc)
  }

  fun testDefaultsWhenKeysAreMissing() {
    val config = FoundryProject.parseConfig("""{"src":"src"}""")
    assertNull(config.evmVersion)
    assertFalse(config.viaIr)
    assertFalse(config.optimizer)
    assertEquals(200, config.optimizerRuns)
  }

  fun testRequestCarriesSettings() {
    val config = FoundryProject.parseConfig(forgeConfigJson)
    val request = SolcRequest.build("src/A.sol", "contract A {}", mapOf("src/B.sol" to "contract B {}"), config, Version(0, 8, 28))
    val settings = request["settings"]
    assertEquals("paris", settings["evmVersion"].asText())
    assertTrue(settings["viaIR"].asBoolean())
    assertTrue(settings["optimizer"]["enabled"].asBoolean())
    assertEquals(1000, settings["optimizer"]["runs"].asInt())
    assertEquals("forge-std/=lib/forge-std/src/", settings["remappings"][0].asText())
    assertEquals(0, settings["outputSelection"].size())
    assertEquals("contract A {}", request["sources"]["src/A.sol"]["content"].asText())
    assertEquals("contract B {}", request["sources"]["src/B.sol"]["content"].asText())
  }

  fun testRequestWithoutViaIrOrEvmVersion() {
    val config = FoundryProject.parseConfig("""{"evm_version":"cancun","via_ir":false,"optimizer":false,"optimizer_runs":200}""")
    val settings = SolcRequest.build("A.sol", "", emptyMap(), config, null, withEvmVersion = false)["settings"]
    assertFalse(settings.has("evmVersion"))
    assertFalse(settings.has("viaIR"))
    assertFalse(settings["optimizer"]["enabled"].asBoolean())
  }

  fun testEvmVersionIsLoweredToWhatSolcSupports() {
    assertEquals("cancun", SolcRequest.normalizeEvmVersion("osaka", Version(0, 8, 24)))
    assertEquals("prague", SolcRequest.normalizeEvmVersion("osaka", Version(0, 8, 28)))
    assertEquals("osaka", SolcRequest.normalizeEvmVersion("osaka", Version(0, 8, 30)))
    assertEquals("paris", SolcRequest.normalizeEvmVersion("cancun", Version(0, 8, 19)))
    assertEquals("london", SolcRequest.normalizeEvmVersion("london", Version(0, 8, 24)))
    assertEquals("osaka", SolcRequest.normalizeEvmVersion("osaka", null))
    assertEquals("amsterdam", SolcRequest.normalizeEvmVersion("amsterdam", Version(0, 8, 24)))
  }

  fun testVersionOfSvmBinary() {
    assertEquals(Version(0, 8, 24), SolcRequest.versionOf("/home/u/.svm/0.8.24/solc-0.8.24"))
    assertNull(SolcRequest.versionOf("/usr/local/bin/solc"))
  }
}
