package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSyncDataTest {
    @Test
    fun syncRoundTripPreservesAgentSettings() {
        val agentSettings = AgentSettings(
            agents = listOf(
                AgentConfig(
                    id = "agent-1",
                    name = "Coda",
                    command = "coda-acp",
                    args = "--yolo",
                ),
            ),
        )
        val syncData = SettingsSyncData(
            providers = emptyList(),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            agentSettings = agentSettings,
        )

        val restored = SettingsSyncData.fromJson(syncData.toJson())

        assertEquals(agentSettings, restored?.agentSettings)
    }

    @Test
    fun syncDataWithoutAgentsUsesEmptyAgentSettings() {
        val restored = SettingsSyncData.fromJson(
            """{"version":2,"providers":[],"themeMode":"SYSTEM","lightColorTheme":"Default","darkColorTheme":"Default"}""",
        )

        assertTrue(restored?.agentSettings?.agents?.isEmpty() == true)
    }

    @Test
    fun syncRoundTripPreservesMonthlyBudget() {
        val syncData = SettingsSyncData(
            providers = listOf(
                ProviderConfig(
                    id = "openai",
                    kind = ProviderKind.OPENAI,
                    apiKey = "key",
                    monthlyBudgetUsd = 42.50,
                ),
            ),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
        )

        val restored = SettingsSyncData.fromJson(syncData.toJson())

        assertEquals(42.50, restored?.providers?.single()?.monthlyBudgetUsd)
    }
}