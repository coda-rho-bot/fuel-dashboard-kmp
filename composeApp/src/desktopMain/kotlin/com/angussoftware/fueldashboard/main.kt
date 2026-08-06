package com.angussoftware.fueldashboard

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.angussoftware.fueldashboard.acp.AcpAgentConfig
import com.angussoftware.fueldashboard.acp.AcpAgentInfo
import com.angussoftware.fueldashboard.acp.AcpAgentManager
import com.angussoftware.fueldashboard.database.DatabaseDriverFactory
import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.server.EmbeddedServer
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun main() = application {
    val viewModel = remember { FuelViewModel() }
    val themeController = ThemeController

    // ── Database (decision history) ──────────────────────────────────────
    val repository = remember { DecisionRepository(DatabaseDriverFactory().createDriver()) }

    // ── Embedded HTTP server for LAN access (mobile devices) ──────────────
    val serverScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val embeddedServer = remember { EmbeddedServer(repository = repository) }

    // ── ACP Agent Manager (desktop-only, monitors fleet agents) ───────────
    val acpScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val acpManager = remember { AcpAgentManager() }

    // Start monitoring fleet agents
    val fleetConfig = AcpAgentConfig.defaultFleet()
    if (fleetConfig.isNotEmpty()) {
        acpManager.startMonitoring(fleetConfig)
    }

    // Collect ACP agent updates → map to UI display models → push into ViewModel
    acpScope.launch {
        acpManager.agents.collect { agentList ->
            val displayList = agentList.map { it.toDisplay() }
            viewModel.updateAcpAgents(displayList)
        }
    }

    // Wire model/mode change callbacks from UI → AcpAgentManager
    viewModel.onAgentModelChange = { agentId, model ->
        acpScope.launch { acpManager.setModel(agentId, model) }
    }
    viewModel.onAgentModeChange = { agentId, mode ->
        // Mode change not yet implemented in AcpAgentManager — placeholder
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

    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
    )

    Window(
        onCloseRequest = {
            acpManager.stopMonitoring()
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
            )
        }
    }
}

/**
 * Map desktop AcpAgentInfo → commonMain AcpAgentDisplay for UI consumption.
 */
private fun AcpAgentInfo.toDisplay() = AcpAgentDisplay(
    id = id,
    name = name,
    currentModel = currentModel,
    availableModels = availableModels,
    currentMode = currentMode,
    availableModes = availableModes,
    status = status.name.lowercase(),
    capabilities = capabilities,
)
