package com.angussoftware.fueldashboard

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import com.angussoftware.fueldashboard.acp.AcpAgentConfig
import com.angussoftware.fueldashboard.acp.AcpAgentInfo
import com.angussoftware.fueldashboard.acp.AcpAgentManager
import com.angussoftware.fueldashboard.acp.AcpAgentStatus
import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.database.DatabaseDriverFactory
import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.server.EmbeddedServer
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.ui.components.parseJunieCredits
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun main() = application {
    val viewModel = remember { FuelViewModel() }
    val themeController = ThemeController
    val junieAuthAvailable = remember { isCommandAvailable("junie-auth") }

    // ── Database (decision history + agent registry) ─────────────────────
    val dbDriver = remember { DatabaseDriverFactory().createDriver() }
    val repository = remember { DecisionRepository(dbDriver) }
    val agentRegistry = remember { AgentRegistry(dbDriver) }

    // ── Embedded HTTP server for LAN access (mobile devices) ──────────────
    val serverScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val embeddedServer = remember {
        EmbeddedServer(
            repository = repository,
            agentRegistry = agentRegistry,
            onProvidersChanged = { viewModel.reloadSettings() },
        )
    }

    // Push ViewModel state → server volatile fields whenever they change
    serverScope.launch {
        viewModel.state.collect { state ->
            embeddedServer.fuelState = state.fuel
            embeddedServer.agents = state.agents.agents
            embeddedServer.alerts = state.alerts.alerts
        }
    }

    embeddedServer.start()

    // ── Set server URL for QR sync ────────────────────────────────────────
    val serverUrl = remember {
        try {
            val conn = java.net.URL("https://fuel.angussoftware.dev").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.requestMethod = "GET"
            conn.responseCode // triggers connection
            conn.disconnect()
            "https://fuel.angussoftware.dev"
        } catch (e: Exception) {
            com.angussoftware.fueldashboard.server.getLanUrl()
        }
    }
    viewModel.setServerUrl(serverUrl)

    // ── Poll MCP/HTTP-registered agents → push to ViewModel ───────────────
    // Agents registered via MCP (POST /agents/register) or HTTP are stored
    // in EmbeddedServer.registeredAgents. Poll them every 5s and merge into
    // the ViewModel's acpAgents so they show in the UI.
    serverScope.launch {
        while (true) {
            val registered = embeddedServer.getRegisteredAgentsForDisplay()
            // Merge with ACP-discovered agents
            val acpAgents = viewModel.state.value.acpAgents
            val merged = mutableListOf<AcpAgentDisplay>()
            // Add ACP agents first
            merged.addAll(acpAgents)
            // Add registered agents that aren't already in the ACP list (avoid duplicates by id)
            for (agent in registered) {
                if (merged.none { it.id == agent.id }) {
                    merged.add(agent)
                }
            }
            if (merged != acpAgents) {
                viewModel.updateAcpAgents(merged)
            }
            kotlinx.coroutines.delay(5_000)
        }
    }

    // ── ACP Agent Manager ─────────────────────────────────────────────────
    val acpScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val agentManager = remember { AcpAgentManager() }

    // Map commonMain AgentConfig → desktop-only AcpAgentConfig
    fun toAcpConfigs(agents: List<AgentConfig>): List<AcpAgentConfig> =
        agents.map { config ->
            AcpAgentConfig(
                id = config.id,
                name = config.name,
                command = config.command,
                args = config.args.split("\\s+".toRegex()).filter { it.isNotBlank() },
                env = config.env,
            )
        }

    // Map AcpAgentInfo → AcpAgentDisplay for the ViewModel/UI layer
    fun toDisplay(infos: List<AcpAgentInfo>): List<AcpAgentDisplay> =
        infos.map { info ->
            AcpAgentDisplay(
                id = info.id,
                name = info.name,
                currentModel = info.currentModel,
                availableModels = info.availableModels,
                currentMode = info.currentMode,
                availableModes = info.availableModes,
                status = when (info.status) {
                    AcpAgentStatus.CONNECTED -> "connected"
                    AcpAgentStatus.CONNECTING -> "connecting"
                    AcpAgentStatus.ERROR -> "error"
                    AcpAgentStatus.DISCONNECTED -> "disconnected"
                },
                capabilities = info.capabilities,
                lastSeen = if (info.status == AcpAgentStatus.CONNECTED) epochMillis() else null,
            )
        }

    // Start monitoring with the initial agent settings (from disk)
    val initialAgentSettings = viewModel.state.value.agentSettings
    if (initialAgentSettings.agents.isNotEmpty()) {
        agentManager.startMonitoring(toAcpConfigs(initialAgentSettings.agents))
    }

    // Push agent manager state → ViewModel whenever it changes
    acpScope.launch {
        agentManager.agents.collect { infos ->
            viewModel.updateAcpAgents(toDisplay(infos))
        }
    }

    // Wire ViewModel callbacks → agent manager (model/mode changes)
    viewModel.onAgentModelChange = { agentId, model ->
        acpScope.launch { agentManager.setModel(agentId, model) }
    }
    viewModel.onAgentModeChange = { _, _ ->
        // Mode change not yet implemented in AcpAgentManager
    }

    // Wire ViewModel callback → EmbeddedServer (agent removal)
    viewModel.onRemoveAgent = { agentId ->
        serverScope.launch { embeddedServer.deleteRegisteredAgent(agentId) }
    }

    if (junieAuthAvailable) {
        viewModel.onJunieCheck = {
            serverScope.launch {
                val output = runCatching {
                    val process = ProcessBuilder("junie-credits")
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
                }.getOrNull()
                viewModel.completeJunieCreditsCheck(output?.let(::parseJunieCredits))
            }
        }
    }

    // Wire ViewModel callback → DecisionRepository (log decisions to SQLite)
    viewModel.onDecisionLogged = { agentId, modelHandle, provider, tier, complexity, utilizationRatio, headroom, reason ->
        serverScope.launch {
            repository.insert(agentId, modelHandle, provider, tier, complexity, utilizationRatio, headroom, reason)
        }
    }

    // Restart agent manager when settings change (add/remove from UI)
    viewModel.onAgentSettingsChanged = { newSettings: AgentSettings ->
        agentManager.stopMonitoring()
        if (newSettings.agents.isNotEmpty()) {
            agentManager.startMonitoring(toAcpConfigs(newSettings.agents))
        } else {
            viewModel.updateAcpAgents(emptyList())
        }
    }

    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
    )

    Window(
        onCloseRequest = {
            agentManager.stopMonitoring()
            viewModel.close()
            embeddedServer.stop()
            exitApplication()
        },
        title = "Fuel Dashboard",
        state = windowState,
    ) {
        DashboardTheme {
            FuelDashboardApp(
                viewModel = viewModel,
                themeController = themeController,
                junieAuthAvailable = junieAuthAvailable,
            )
        }
    }
}

private fun isCommandAvailable(command: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    return path.split(File.pathSeparator).any { directory ->
        File(directory, command).canExecute()
    }
}
