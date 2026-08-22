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
    fun syncRoundTripPreservesSectionOrders() {
        val syncData = SettingsSyncData(
            providers = emptyList(),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            usageSectionOrder = listOf("waste", "metered", "drain"),
            intelSectionOrder = listOf("events"),
        )

        val restored = SettingsSyncData.fromJson(syncData.toJson())

        assertEquals(listOf("waste", "metered", "drain"), restored?.usageSectionOrder)
        assertEquals(listOf("events"), restored?.intelSectionOrder)
    }

    @Test
    fun providerOrderRoundTripsThroughSync() {
        // Provider list order IS the user's display order — it must survive
        // serialization unchanged (no sorting on either side).
        val providers = listOf(
            ProviderConfig(id = "z", kind = ProviderKind.ZAI, apiKey = "k1"),
            ProviderConfig(id = "a", kind = ProviderKind.OPENAI, apiKey = "k2"),
        )
        val syncData = SettingsSyncData(
            providers = providers,
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
        )

        val restored = SettingsSyncData.fromJson(syncData.toJson())

        assertEquals(providers.map { it.id }, restored?.providers?.map { it.id })
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

    @Test
    fun qrPayloadSlimsAgentLauncherFieldsButKeepsIdentity() {
        val syncData = SettingsSyncData(
            providers = emptyList(),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            agentSettings = AgentSettings(
                agents = listOf(
                    AgentConfig(
                        id = "coda",
                        name = "Coda",
                        command = "/home/rhomancer/.nvm/versions/node/v22.22.3/bin/letta-acp",
                        args = "--yolo",
                        env = mapOf(
                            "LETTA_ACP_BACKEND" to "remote",
                            "LETTA_AGENT_ID" to "agent-b499137a-e1dd-4427-b9df-73e87adfce9e",
                            "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14601",
                            "NODE_OPTIONS" to "--experimental-websocket",
                        ),
                    ),
                ),
            ),
        )

        val slim = syncData.slimmedForQr()

        // Launcher fields are desktop-local — they must not ride the QR.
        assertEquals("", slim.agentSettings.agents.single().command)
        assertEquals("", slim.agentSettings.agents.single().args)
        assertTrue(slim.agentSettings.agents.single().env.isEmpty())
        // Identity survives — mobile renders agent names from these.
        assertEquals("coda", slim.agentSettings.agents.single().id)
        assertEquals("Coda", slim.agentSettings.agents.single().name)

        // The slim QR payload must parse back.
        val restored = SettingsSyncData.fromQrData(syncData.toQrData())
        assertEquals("Coda", restored?.agentSettings?.agents?.single()?.name)
    }

    @Test
    fun harryScaleConfigStaysReliablyScannable() {
        // 5 fleet agents with full launcher env + 4 providers with realistic
        // API keys — the Aug 22 "QR no detection" regression payload class.
        val agents = (1..5).map { i ->
            AgentConfig(
                id = "agent-$i",
                name = "Agent $i",
                command = "/home/rhomancer/.nvm/versions/node/v22.22.3/bin/letta-acp",
                args = "--yolo",
                env = mapOf(
                    "LETTA_ACP_BACKEND" to "remote",
                    "LETTA_AGENT_ID" to "agent-b499137a-e1dd-4427-b9df-00000000000$i",
                    "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:1460$i",
                    "NODE_OPTIONS" to "--experimental-websocket",
                ),
            )
        }
        val providers = listOf(
            ProviderConfig(id = "openai", kind = ProviderKind.OPENAI, apiKey = "sk-proj-9aXbQ2mZ7hT4kLpR8wYcV1nD6fJ0sG3eH5uI2oP7qA4rB8tC1xW6yE9zK3mN5vL", displayName = "OpenAI"),
            ProviderConfig(id = "anthropic", kind = ProviderKind.ANTHROPIC, apiKey = "sk-ant-api03-Kj8Hg2Lp9Qw7Er4Ty1Ui6Op3As5Df0Gh8Jk2Lz9Xc4Vb7Nm1Qw6Er3Ty5Ui8", displayName = "Anthropic"),
            ProviderConfig(id = "zai", kind = ProviderKind.ZAI, apiKey = "zai-3f7b9c1d2e4a6b8c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c"),
            ProviderConfig(id = "groq", kind = ProviderKind.GROQ, apiKey = "gsk_AbCdEf12GhIjKl34MnOpQr56StUvWx78YzAbCd90EfGhIj12KlMnOp34QrStUv"),
        )
        val syncData = SettingsSyncData(
            providers = providers,
            themeMode = "SYSTEM",
            lightColorTheme = "Angus",
            darkColorTheme = "AngusDark",
            serverUrl = "https://fuel.angussoftware.dev",
            serverApiKey = "fd-1234567890abcdef1234567890abcdef",
            agentSettings = AgentSettings(agents = agents),
        )

        val qrData = syncData.toQrData()
        val version = minimumInformationDensityFor(qrData)

        // Lesson #84: versions ≤ 20 scan reliably off a screen; 21-30 are
        // marginal (the Aug 22 failure was version 24). Hard bound at 20.
        assertTrue(version <= 20, "QR version $version exceeds the reliably-scannable bound (20); payload=${qrData.length} chars")
    }
}

/** Smallest QR version (ECC LOW) that fits [data] — mirrors the app's minimumInformationDensity. */
private fun minimumInformationDensityFor(data: String): Int {
    val processor = qrcode.raw.QRCodeProcessor(data, qrcode.raw.ErrorCorrectionLevel.LOW)
    for (version in 1..qrcode.raw.QRCodeProcessor.MAXIMUM_INFO_DENSITY) {
        if (runCatching { processor.encode(version) }.isSuccess) return version
    }
    return qrcode.raw.QRCodeProcessor.MAXIMUM_INFO_DENSITY
}