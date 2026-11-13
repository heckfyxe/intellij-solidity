package me.serce.solidity.ide.foundry.toml

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FoundryTomlTest : BasePlatformTestCase() {
  fun testUnknownKeys() {
    myFixture.enableInspections(FoundryTomlUnknownKeyInspection::class.java)
    myFixture.configureByText(
      "foundry.toml",
      """
        [profile.default]
        src = "src"
        <warning descr="Unknown foundry.toml key 'optimiser'">optimiser</warning> = true
        fuzz.runs = 10
        fuzz.<warning descr="Unknown foundry.toml key 'fuzz.runz'">runz</warning> = 10
        solc_version = "0.8.24"

        [profile.default.invariant]
        depth = 15
        <warning descr="Unknown foundry.toml key 'invariant.deep'">deep</warning> = 1

        [profile.ci.<warning descr="Unknown foundry.toml key 'fuzzz'">fuzzz</warning>]
        runs = 1

        [fmt]
        line_length = 120
        <warning descr="Unknown foundry.toml key 'fmt.line_len'">line_len</warning> = 1

        [rpc_endpoints]
        mainnet = "${'$'}{MAINNET_RPC_URL}"

        [etherscan]
        mainnet = { key = "${'$'}{ETHERSCAN_KEY}" }

        [profile.default.optimizer_details]
        yul = true

        [something_else]
        anything = 1
      """.trimIndent()
    )
    myFixture.checkHighlighting()
  }

  fun testKeyCompletion() {
    myFixture.configureByText("foundry.toml", "[profile.default]\noptimizer_r<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("[profile.default]\noptimizer_runs<caret>")
  }

  fun testNestedKeyCompletion() {
    myFixture.configureByText("foundry.toml", "[profile.default.fuzz]\nru<caret>")
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertContainsElements(items, "runs", "run")
    assertDoesntContain(items, "src")
  }

  fun testHeaderCompletion() {
    myFixture.configureByText("foundry.toml", "[profile.default.inv<caret>]")
    myFixture.completeBasic()
    myFixture.checkResult("[profile.default.invariant<caret>]")
  }

  fun testEnumValueCompletion() {
    myFixture.configureByText("foundry.toml", "[profile.default]\nevm_version = \"can<caret>\"")
    myFixture.completeBasic()
    myFixture.checkResult("[profile.default]\nevm_version = \"cancun<caret>\"")
  }

  fun testBooleanValueCompletion() {
    myFixture.configureByText("foundry.toml", "[profile.default]\nvia_ir = tr<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("[profile.default]\nvia_ir = true<caret>")
  }

  fun testOtherTomlFilesAreLeftAlone() {
    myFixture.enableInspections(FoundryTomlUnknownKeyInspection::class.java)
    myFixture.configureByText("Cargo.toml", "[profile.default]\nanything = 1\n")
    myFixture.checkHighlighting()
  }
}
