package me.serce.solidity.chain

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.UnixProcessManager
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import me.serce.solidity.chain.abi.AbiCodec
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.chain.rpc.JsonRpcClient
import me.serce.solidity.chain.rpc.RpcException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ChainStatus { STOPPED, STARTING, RUNNING, FAILED }

/** Live state of a chain while the IDE is open. */
class ChainRuntime(val configId: String) {
  @Volatile var status: ChainStatus = ChainStatus.STOPPED
  @Volatile var rpc: JsonRpcClient? = null
  @Volatile var accounts: List<String> = emptyList()
  @Volatile var balances: Map<String, BigInteger> = emptyMap()
  @Volatile var chainId: Long? = null
  @Volatile var blockNumber: Long? = null
  @Volatile var error: String? = null
  @Volatile var process: KillableColoredProcessHandler? = null
  @Volatile var console: ConsoleView? = null
  /** Deployments whose address no longer has code (chain was reset). */
  @Volatile var missing: Set<String> = emptySet()
  /** The "fork cannot mine" notification was shown for this run. */
  @Volatile var staleForkNotified = false
}

fun chainNotification(title: String, content: String, type: NotificationType): Notification =
  NotificationGroupManager.getInstance().getNotificationGroup("Solidity Chains").createNotification(title, content, type)

fun interface ChainListener {
  fun chainsChanged()

  companion object {
    val TOPIC = Topic.create("Solidity chains changed", ChainListener::class.java)
  }
}

@Service(Service.Level.PROJECT)
@State(name = "SolidityChains", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ChainService(private val project: Project) : PersistentStateComponent<ChainState>, Disposable {
  private var state = ChainState()
  private val runtimes = ConcurrentHashMap<String, ChainRuntime>()
  /** Immutable copy for background readers (highlighting, executor); replaced after each change on the EDT. */
  @Volatile private var deploymentsSnapshot: List<Deployment> = emptyList()
  /** One task for the whole service: also when the IDE exits without disposing services (crash, kill), don't leave anvil running. */
  private val shutdownTask = Runnable { interruptAll() }

  init {
    ShutDownTracker.getInstance().registerShutdownTask(shutdownTask)
  }

  override fun getState(): ChainState = state
  override fun loadState(loaded: ChainState) {
    state = loaded
    // Nothing runs before the project opens, so a temporary chain's leftovers are from a session that ended
    // without its stop being recorded (the IDE closed while it ran).
    loaded.chains.filter { it.kind == ChainKind.ANVIL && !it.persistent }.forEach { dropChainData(it.id) }
    deploymentsSnapshot = loaded.deployments.toList()
  }

  val chains: List<ChainConfig> get() = state.chains.toList()
  var compileWhileEditing: Boolean
    get() = state.compileWhileEditing
    set(value) {
      state.compileWhileEditing = value
      DaemonCodeAnalyzer.getInstance(project).restart()
    }

  fun chain(id: String) = state.chains.firstOrNull { it.id == id }
  fun runtime(id: String): ChainRuntime = runtimes.computeIfAbsent(id) { ChainRuntime(it) }
  fun isRunning(config: ChainConfig) = runtime(config.id).status == ChainStatus.RUNNING
  fun runningChains(): List<ChainConfig> = chains.filter(::isRunning)
  fun deployments(chainId: String) = deploymentsSnapshot.filter { it.chainId == chainId }
  fun allDeployments(): List<Deployment> = deploymentsSnapshot
  fun history(): List<TxRecord> = state.history.toList()

  fun selectedAccount(chainId: String): String? =
    state.selectedAccount[chainId]?.takeIf { it in runtime(chainId).accounts } ?: runtime(chainId).accounts.firstOrNull()

  /** Called from a form's sender combo: publish later, so listeners don't rebuild the form inside its own event. */
  fun selectAccount(chainId: String, account: String) = invokeLater { mutate { state.selectedAccount[chainId] = account } }

  // ---- configuration ----

  fun addChain(config: ChainConfig) = mutate { state.chains.add(config) }

  /** The state file is keyed by what it depends on ([stateFile]), so editing the fork needs no cleanup here. */
  fun updateChain(config: ChainConfig) = mutate {
    val i = state.chains.indexOfFirst { it.id == config.id }
    if (i >= 0) state.chains[i] = config
  }

  fun removeChain(config: ChainConfig) {
    stop(config)
    mutate(restartHighlighting = true) {
      state.chains.removeIf { it.id == config.id }
      dropChainData(config.id)
      state.selectedAccount.remove(config.id)
      // the process may still be exiting; onStopped ignores chains without a runtime
      runtimes.remove(config.id)?.console?.let(Disposer::dispose)
      stateFiles(config).forEach { Files.deleteIfExists(it) }
    }
  }

  /** Deployments and history belong to the chain's state: they go when it is removed or a temporary chain stops. */
  private fun dropChainData(chainId: String) {
    state.deployments.removeIf { it.chainId == chainId }
    state.history.removeIf { it.chainId == chainId }
  }

  fun addDeployment(d: Deployment) = mutate(restartHighlighting = true) { state.deployments.add(d) }

  fun removeDeployment(d: Deployment) = mutate(restartHighlighting = true) { state.deployments.removeIf { it.id == d.id } }

  fun addRecord(record: TxRecord) = mutate {
    state.history.add(0, record)
    while (state.history.size > MAX_HISTORY) state.history.removeAt(state.history.size - 1)
  }

  fun clearHistory() = mutate { state.history.clear() }

  /** Tells listeners that runtime state (status, balances, …) changed. */
  fun fireChanged() = mutate {}

  /** Saved state is only touched on the EDT and every change is published from here, so callers on any thread go through it. */
  private fun mutate(restartHighlighting: Boolean = false, change: () -> Unit) {
    val apply = {
      if (!project.isDisposed) {
        change()
        deploymentsSnapshot = state.deployments.toList()
        if (restartHighlighting) DaemonCodeAnalyzer.getInstance(project).restart()
        project.messageBus.syncPublisher(ChainListener.TOPIC).chainsChanged()
      }
    }
    if (ApplicationManager.getApplication().isDispatchThread) apply() else invokeLater { apply() }
  }

  // ---- lifecycle ----

  fun start(config: ChainConfig) {
    val rt = runtime(config.id)
    if (rt.status == ChainStatus.RUNNING || rt.status == ChainStatus.STARTING) return
    rt.status = ChainStatus.STARTING
    rt.error = null
    rt.staleForkNotified = false
    fireChanged()
    if (config.kind == ChainKind.EXTERNAL) {
      ApplicationManager.getApplication().executeOnPooledThread { connect(config, JsonRpcClient(config.url), attempts = 1) }
      return
    }
    try {
      val foundry = FoundryProject.find(project)
      val anvil = FoundryProject.anvilPath(project)
      if (!Path.of(anvil).toFile().canExecute()) throw IllegalStateException("anvil not found at $anvil. Install Foundry with foundryup.")
      val port = freePort(config.port)
      val cmd = GeneralCommandLine(anvilArgs(config, anvil, port)).withEnvironment("NO_COLOR", "1")
      foundry?.let { cmd.withWorkDirectory(it.root.toFile()) }
      val handler = KillableColoredProcessHandler(cmd)
      handler.setShouldKillProcessSoftly(true) // SIGINT, so anvil dumps --state before exiting
      rt.process = handler
      val console = rt.console ?: TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also {
        Disposer.register(this, it)
        rt.console = it
      }
      console.print("$ ${cmd.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      console.attachToProcess(handler)
      // Connect only once *our* anvil says where it listens, so we never talk to another node on the same port.
      val listening = CompletableFuture<String>()
      val tail = ArrayDeque<String>()
      handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
          synchronized(tail) {
            event.text.lines().filter { it.isNotBlank() }.forEach { tail.addLast(it.trim()); if (tail.size > 15) tail.removeFirst() }
          }
          LISTENING.find(event.text)?.let { listening.complete("http://127.0.0.1:${it.groupValues[1]}") }
        }

        override fun processTerminated(event: ProcessEvent) {
          val output = synchronized(tail) { tail.toList() }
          listening.completeExceptionally(IllegalStateException(startupError(event.exitCode, output)))
          onStopped(config, event.exitCode, output)
        }
      })
      handler.startNotify()
      ApplicationManager.getApplication().executeOnPooledThread {
        val url = try {
          listening.get(if (config.isFork) 180L else 60L, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
          return@executeOnPooledThread // onStopped reported it
        } catch (e: TimeoutException) {
          fail(config, "anvil did not start listening in time" + if (config.isFork) " (is the fork RPC reachable?)" else "")
          stop(config)
          return@executeOnPooledThread
        }
        connect(config, JsonRpcClient(url), attempts = 20)
      }
    } catch (e: Exception) {
      fail(config, e.message ?: e.toString())
    }
  }

  /** Stops [config] if it runs and starts it again once anvil has exited (and saved its state). */
  fun restart(config: ChainConfig) {
    val process = runtime(config.id).process
    if (process == null || process.isProcessTerminated) return start(config)
    process.addProcessListener(object : ProcessListener {
      override fun processTerminated(event: ProcessEvent) = invokeLater { start(config) }
    })
    stop(config)
  }

  /** Once per run: [config]'s fork RPC dropped the fork block's state, and restarting forks again from the latest block. */
  fun notifyStaleFork(config: ChainConfig) {
    val rt = runtime(config.id)
    if (rt.staleForkNotified) return
    rt.staleForkNotified = true
    val keeps = if (config.persistent) "Its accounts and deployments are kept."
    else "This is a temporary chain, so its deployments are lost."
    chainNotification(
      "${config.name} cannot mine any more",
      "The fork RPC no longer serves the state of the fork block. Restart the chain to fork again from the latest block. " +
        "$keeps Fork from an archive RPC to avoid this.",
      NotificationType.ERROR,
    ).addAction(NotificationAction.createSimpleExpiring("Restart Chain") { restart(config) }).notify(project)
  }

  fun stop(config: ChainConfig) {
    val rt = runtime(config.id)
    val process = rt.process
    if (process != null && !process.isProcessTerminated) {
      process.destroyProcess()
    } else {
      onStopped(config, null)
    }
  }

  /** anvil's own error line (e.g. "Address already in use", a failing fork RPC) beats an exit code. */
  private fun startupError(exitCode: Int, output: List<String>): String {
    val reason = output.lastOrNull { it.contains("error", ignoreCase = true) } ?: output.lastOrNull()
    return reason?.removePrefix("Error:")?.trim()?.let { "anvil stopped: $it" } ?: "anvil exited with code $exitCode, see its console"
  }

  /** Re-reads accounts, balances and block number. */
  fun refresh(config: ChainConfig) {
    val rt = runtime(config.id)
    val rpc = rt.rpc ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        loadChainInfo(config, rt, rpc)
      } catch (e: RpcException) {
        LOG.info(e)
      }
      fireChanged()
    }
  }

  private fun connect(config: ChainConfig, rpc: JsonRpcClient, attempts: Int) {
    val rt = runtime(config.id)
    var lastError: Exception? = null
    repeat(attempts) {
      if (rt.status != ChainStatus.STARTING) return
      try {
        rt.chainId = rpc.chainId()
        rt.rpc = rpc
        if (config.isFork) clearForkedAccountCode(rt, rpc)
        loadChainInfo(config, rt, rpc)
        rt.status = ChainStatus.RUNNING
        fireChanged()
        return
      } catch (e: RpcException) {
        lastError = e
        Thread.sleep(500)
      }
    }
    fail(config, lastError?.message ?: "Chain did not start")
    if (config.kind == ChainKind.ANVIL) stop(config)
  }

  /**
   * anvil's dev accounts come from a public mnemonic, so on real networks they carry code planted by others,
   * such as EIP-7702 delegations that sweep every incoming transfer to someone else's address.
   * A fork copies that code, so dev accounts would silently forward ETH sent to them; make them plain accounts again.
   */
  private fun clearForkedAccountCode(rt: ChainRuntime, rpc: JsonRpcClient) {
    for (account in rpc.accounts()) {
      if (rpc.code(account).isEmpty()) continue
      rpc.call("anvil_setCode", account, "0x")
      rt.console?.print("Cleared code that ${AbiCodec.checksum(account)} has on the forked network\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }
  }

  private fun loadChainInfo(config: ChainConfig, rt: ChainRuntime, rpc: JsonRpcClient) {
    rt.accounts = rpc.accounts().map { AbiCodec.checksum(it) }
    rt.balances = rt.accounts.associateWith { rpc.balance(it) }
    rt.blockNumber = rpc.blockNumber()
    rt.missing = deployments(config.id).filter { rpc.code(it.address).isEmpty() }.map { it.id }.toSet()
  }

  private fun onStopped(config: ChainConfig, exitCode: Int?, output: List<String> = emptyList()) {
    val rt = runtimes[config.id] ?: return // removed meanwhile
    val wasStarting = rt.status == ChainStatus.STARTING
    if (wasStarting && exitCode != null && exitCode != 0) {
      fail(config, startupError(exitCode, output))
    } else if (rt.status != ChainStatus.FAILED) {
      rt.status = ChainStatus.STOPPED
    }
    rt.rpc = null
    rt.process = null
    rt.balances = emptyMap()
    mutate(restartHighlighting = true) {
      if (config.kind == ChainKind.ANVIL && !config.persistent) dropChainData(config.id)
    }
  }

  private fun fail(config: ChainConfig, message: String) {
    val rt = runtime(config.id)
    rt.status = ChainStatus.FAILED
    rt.error = message
    fireChanged()
    chainNotification("${config.name} failed to start", message, NotificationType.ERROR).notify(project)
  }

  fun anvilArgs(config: ChainConfig, anvil: String, port: Int): List<String> {
    val args = mutableListOf(anvil, "--port", port.toString(), "--accounts", config.accounts.toString(), "--balance", config.balanceEth.toString())
    if (config.chainId.isNotBlank()) args += listOf("--chain-id", config.chainId.trim())
    if (config.isFork) {
      args += listOf("--fork-url", config.forkUrl.trim())
      if (config.forkBlock.isNotBlank()) args += listOf("--fork-block-number", config.forkBlock.trim())
    }
    if (config.persistent) {
      val file = stateFile(config)
      Files.createDirectories(file.parent)
      // states saved for this chain's earlier fork settings can never be loaded again
      stateFiles(config).filter { it != file }.forEach { Files.deleteIfExists(it) }
      args += listOf("--state", file.toString(), "--state-interval", "10")
    }
    return args
  }

  private val stateDir: Path get() = Path.of(project.basePath ?: System.getProperty("java.io.tmpdir"), ".idea", "chain-states")

  /**
   * Named after what the state depends on, so a state saved on one fork is never loaded onto another,
   * even when anvil writes it again after the fork was edited.
   */
  fun stateFile(config: ChainConfig): Path {
    val file = stateDir.resolve("${config.id}-${config.stateKey}.json")
    // states from before the key was added belong to the current settings, which used to delete them on change
    val legacy = stateDir.resolve("${config.id}.json")
    if (!Files.exists(file) && Files.exists(legacy)) runCatching { Files.move(legacy, file) }
    return file
  }

  private fun stateFiles(config: ChainConfig): List<Path> =
    stateDir.toFile().listFiles { f -> f.name == "${config.id}.json" || f.name.startsWith("${config.id}-") }.orEmpty().map { it.toPath() }

  private fun freePort(preferred: Int): Int {
    for (p in preferred until preferred + 50) {
      if (isFree(p)) return p
    }
    return ServerSocket(0).use { it.localPort }
  }

  private fun isFree(port: Int): Boolean {
    val answering = try {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
    } catch (_: Exception) {
      false
    }
    if (answering) return false
    return try {
      ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress("127.0.0.1", port)); true }
    } catch (_: Exception) {
      false
    }
  }

  override fun dispose() {
    interruptAll()
    ShutDownTracker.getInstance().unregisterShutdownTask(shutdownTask)
  }

  private fun interruptAll() = runtimes.values.forEach { rt -> rt.process?.let { interrupt(it) } }

  /** SIGINT lets anvil write its --state file before exiting; SIGTERM would lose the last changes. */
  private fun interrupt(handler: KillableColoredProcessHandler) {
    if (handler.isProcessTerminated) return
    if (SystemInfo.isUnix) UnixProcessManager.sendSignal(handler.process.pid().toInt(), UnixProcessManager.SIGINT)
    else handler.destroyProcess()
  }

  companion object {
    private val LOG = logger<ChainService>()
    private const val MAX_HISTORY = 500
    private val LISTENING = Regex("""Listening on [^\s:]+:(\d+)""")
    fun getInstance(project: Project): ChainService = project.service()
  }
}
