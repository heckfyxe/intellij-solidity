package me.serce.solidity.ide.run

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.serce.solidity.resolveForgeExecutable
import me.serce.solidity.settings.ConfigurationMode
import me.serce.solidity.settings.SoliditySettings
import me.serce.solidity.settings.SoliditySettingsState
import me.serce.solidity.testutil.TestExecutable
import java.nio.file.Paths

class ForgeTestSettingsTest : BasePlatformTestCase() {

    private lateinit var originalSettings: SoliditySettingsState

    override fun setUp() {
        super.setUp()
        val settings = SoliditySettings.getInstance(project)
        originalSettings = SoliditySettingsState().also { it.copyFrom(settings.state) }
        project.basePath
    }

    override fun tearDown() = try {
        val settings = SoliditySettings.getInstance(project)
        settings.loadState(originalSettings)
    } finally {
        super.tearDown()
    }

    fun testResolveForgeExecutableMac() {
        withUserHome("TEST_HOME") {
            val settings = SoliditySettings()
            settings.testFoundryExecutablePath = ""
            settings.testFoundryConfigurationMode = ConfigurationMode.AUTOMATIC

            val resolved =
                resolveForgeExecutable(settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, false)

            val expected = "TEST_HOME/.foundry/bin/forge"
            assertEquals(expected, resolved)
        }
    }

    fun testResolveForgeExecutableWindows() {
        withUserHome("TEST_HOME") {
            val settings = SoliditySettings()
            settings.formatterFoundryExecutablePath = ""
            settings.testFoundryConfigurationMode = ConfigurationMode.AUTOMATIC

            val resolved =
                resolveForgeExecutable(settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, true)

            // Ideally, this test would verify the win separator, but the Paths.get behaviour isn't mockable.
            val expected = "TEST_HOME/.foundry/bin/forge.exe"
            assertEquals(expected, resolved)
        }
    }

    fun testExecuteFoundryTestGutterWithAutomaticPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.AUTOMATIC, false, "")

    fun testExecuteFoundryTestGutterWithManualPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.MANUAL, false, "")

    fun testExecuteFoundryTestGutterWithAutomaticPathWindows() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.AUTOMATIC, true, "")

    fun testExecuteFoundryTestGutterWithManualPathWindows() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.MANUAL, true, "")

    fun testExecuteFoundryTestGutterWithAutomaticPathAndConfigPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.AUTOMATIC, false, "not/blank/path")

    fun testExecuteFoundryTestGutterWithManualPathAndConfigPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.MANUAL, false, "not/blank/path")

    fun testExecuteFoundryTestGutterWithAutomaticPathWindowsAndConfigPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.AUTOMATIC, true, "not/blank/path")

    fun testExecuteFoundryTestGutterWithManualPathWindowsAndConfigPath() =
        checkPathWithForgeTestCommandLineState(ConfigurationMode.MANUAL, true, "not/blank/path")

    fun testExecuteFoundryTestGutterWithAutomaticPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.AUTOMATIC,
            false,
            "",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithManualPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.MANUAL,
            false,
            "",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithAutomaticPathWindowsAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.AUTOMATIC,
            true,
            "",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithManualPathWindowsAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.MANUAL,
            true,
            "",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithAutomaticPathConfigPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.AUTOMATIC,
            false,
            configPath = "not/blank/path",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithManualPathConfigPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.MANUAL,
            false,
            configPath = "not/blank/path",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithAutomaticPathWindowsConfigPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.AUTOMATIC,
            true,
            configPath = "not/blank/path",
            workingDirectory = "not/blank/working/dir"
        )

    fun testExecuteFoundryTestGutterWithManualPathWindowsConfigPathAndWorkingDirectory() =
        checkPathWithForgeTestCommandLineState(
            ConfigurationMode.MANUAL,
            true,
            configPath = "not/blank/path",
            workingDirectory = "not/blank/working/dir"
        )

    private fun checkPathWithForgeTestCommandLineState(
        configurationMode: ConfigurationMode, isWindows: Boolean, configPath: String, workingDirectory: String = ""
    ) {
        val forge = TestExecutable.Builder(
            "forge", TestExecutable.Workdir.UnderDir(Paths.get(myFixture.tempDirPath)), testRootDisposable
        ).exitCode(0).build()

        val settings = SoliditySettings.getInstance(project).apply {
            testFoundryConfigurationMode = configurationMode
            testFoundryExecutablePath = forge.path
            testFoundryConfigPath = configPath
        }

        val configurationType = ForgeTestRunConfigurationType()
        val factory = ForgeTestRunConfigurationFactory(configurationType)
        val configuration = ForgeTestRunConfiguration(project, factory, "Test Configuration")

        configuration.workingDirectory = workingDirectory
        configuration.testName = "testIncrement"
        configuration.contractName = "CounterTest"

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val configurationSettings = RunManager.getInstance(project).createConfiguration(configuration, factory)
        val env = ExecutionEnvironmentBuilder.create(executor, configurationSettings).build()

        val commandLineState = ForgeTestCommandLineState(configuration, env)

        val generatedCommandLine = commandLineState.generateCommandLine(isWindows)

        val resolved = resolveForgeExecutable(
            settings.testFoundryExecutablePath, settings.testFoundryConfigurationMode, isWindows
        )
        assertEquals(resolved, generatedCommandLine.toString().split(" ").first())
        assertTrue(
            if (configuration.workingDirectory.isNotBlank()) {
                !generatedCommandLine.parametersList.parameters.contains("--root")
                    .and(generatedCommandLine.parametersList.parameters.contains(workingDirectory))
            } else if (settings.testFoundryConfigurationMode == ConfigurationMode.AUTOMATIC) {
                !generatedCommandLine.toString().contains("--root")
            } else if (!configPath.isBlank()) {
                generatedCommandLine.parametersList.parameters.contains("--root")
                    .and(generatedCommandLine.parametersList.parameters.contains(configPath))
            } else {
                !generatedCommandLine.parametersList.parameters.contains("--root")
                    .and(generatedCommandLine.parametersList.parameters.contains(configPath))
            }
        )
    }

    fun testTestAndContractFiltersAreAnchored() {
        val configuration = ForgeTestRunConfiguration(project, ForgeTestRunConfigurationFactory(ForgeTestRunConfigurationType()), "t")
        configuration.workingDirectory = myFixture.tempDirPath
        configuration.testName = "testFoo"
        configuration.contractName = "FooTest"
        val settings = RunManager.getInstance(project).createConfiguration(configuration, configuration.factory!!)
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val parameters = ForgeTestCommandLineState(configuration, env).generateCommandLine().parametersList.parameters
        assertEquals("^testFoo\\(", parameters[parameters.indexOf("--match-test") + 1])
        assertEquals("^FooTest$", parameters[parameters.indexOf("--match-contract") + 1])

        val rerun = ForgeTestCommandLineState(configuration, env, mapOf("ATest" to setOf("testA", "testB"), "BTest" to setOf("testC")))
            .generateCommandLine().parametersList.parameters
        assertEquals("^(testA|testB|testC)\\(", rerun[rerun.indexOf("--match-test") + 1])
        assertEquals("^(ATest|BTest)$", rerun[rerun.indexOf("--match-contract") + 1])
    }

    fun testProfileForkSeedAndArguments() {
        val configuration = ForgeTestRunConfiguration(project, ForgeTestRunConfigurationFactory(ForgeTestRunConfigurationType()), "t")
        configuration.workingDirectory = myFixture.tempDirPath
        configuration.profile = "ci"
        configuration.forkUrl = "mainnet"
        configuration.fuzzSeed = "0x2a"
        configuration.additionalArguments = "--gas-report --match-path \"test/a b.t.sol\""
        val settings = RunManager.getInstance(project).createConfiguration(configuration, configuration.factory!!)
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val cmd = ForgeTestCommandLineState(configuration, env).generateCommandLine()
        val parameters = cmd.parametersList.parameters
        assertEquals("mainnet", parameters[parameters.indexOf("--fork-url") + 1])
        assertEquals("0x2a", parameters[parameters.indexOf("--fuzz-seed") + 1])
        assertEquals(listOf("--gas-report", "--match-path", "test/a b.t.sol"), parameters.takeLast(3))
        assertEquals("ci", cmd.environment["FOUNDRY_PROFILE"])
        configuration.checkConfiguration()

        // the new fields survive a save and load
        val element = org.jdom.Element("configuration")
        configuration.writeExternal(element)
        val loaded = ForgeTestRunConfiguration(project, ForgeTestRunConfigurationFactory(ForgeTestRunConfigurationType()), "t")
        loaded.readExternal(element)
        assertEquals(listOf("ci", "mainnet", "0x2a", configuration.additionalArguments),
            listOf(loaded.profile, loaded.forkUrl, loaded.fuzzSeed, loaded.additionalArguments))

        configuration.fuzzSeed = "abc"
        assertThrows(com.intellij.execution.configurations.RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
    }

    private fun withUserHome(fakeHome: String, block: () -> Unit) {
        val old = System.getProperty("user.home")
        try {
            System.setProperty("user.home", fakeHome)
            block()
        } finally {
            if (old == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", old)
            }
        }
    }
}
