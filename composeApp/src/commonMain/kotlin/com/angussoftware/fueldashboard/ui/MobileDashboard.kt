package com.angussoftware.fueldashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.components.AgentFleetPanel
import com.angussoftware.fueldashboard.ui.components.AlertsPanel
import com.angussoftware.fueldashboard.ui.components.SettingsPanel

private enum class MobileTab(val label: String) {
    FUEL("Fuel"),
    FLEET("Fleet"),
    SETTINGS("Settings"),
}

@Composable
fun MobileDashboard(
    state: DashboardState,
    themeController: ThemeController,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(MobileTab.FUEL.ordinal) }

    Column(modifier = modifier.fillMaxSize()) {
        // Content area — fills space above the nav bar
        when (MobileTab.entries.getOrElse(selectedTab) { MobileTab.FUEL }) {
            MobileTab.FUEL -> {
                FuelColumnContent(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }

            MobileTab.FLEET -> {
                FleetTabContent(
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                )
            }

            MobileTab.SETTINGS -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsPanel(
                        themeController = themeController,
                        settings = state.settings,
                        viewModel = viewModel,
                    )
                }
            }
        }

        // Bottom navigation bar
        NavigationBar {
            MobileTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab.ordinal,
                    onClick = { selectedTab = tab.ordinal },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                MobileTab.FUEL -> Icons.Default.LocalGasStation
                                MobileTab.FLEET -> Icons.Default.Group
                                MobileTab.SETTINGS -> Icons.Default.Settings
                            },
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

@Composable
private fun FleetTabContent(
    state: DashboardState,
    modifier: Modifier = Modifier,
) {
    if (!state.hasConnectedApi) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No orchestrator connected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a Connected API provider in Settings to see fleet data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentFleetPanel(agents = state.agents.agents)
        AlertsPanel(alerts = state.alerts.toFuelAlerts())

        // Show decision history on fleet tab too (it was on the fuel column on desktop)
        val decisions = state.decisions.decisions
        if (decisions.isNotEmpty()) {
            com.angussoftware.fueldashboard.ui.components.DecisionLog(decisions = decisions)
        }
    }
}
