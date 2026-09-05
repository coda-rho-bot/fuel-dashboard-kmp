package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun syncRoundTripPreservesUsageSourcesAndPreferences() {
        val syncData = SettingsSyncData(
            providers = emptyList(),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            usageSources = com.angussoftware.fueldashboard.usage.UsageSourcesSettings(
                letta = com.angussoftware.fueldashboard.usage.LettaSourceConfig(
                    enabled = true,
                    baseUrl = "https://api.letta.com",
                    apiKey = "sk-live-abc123def456ghi789jkl012mno345pqr678stu901vwx234yz",
                ),
            ),
            eventDropThresholdPct = 2.5,
            showHelp = false,
            showThemeIcon = false,
            feedbackUrl = "https://git.example.com",
            feedbackRepo = "acme/custom-repo",
        )

        val restored = SettingsSyncData.fromJson(syncData.toJson())

        assertEquals(true, restored?.usageSources?.letta?.enabled)
        assertEquals("sk-live-abc123def456ghi789jkl012mno345pqr678stu901vwx234yz", restored?.usageSources?.letta?.apiKey)
        assertEquals(2.5, restored?.eventDropThresholdPct)
        assertEquals(false, restored?.showHelp)
        assertEquals(false, restored?.showThemeIcon)
        assertEquals("https://git.example.com", restored?.feedbackUrl)
        assertEquals("acme/custom-repo", restored?.feedbackRepo)
    }

    @Test
    fun scopedQrPayloadsRoundTripWithTheirDomains() {
        val full = SettingsSyncData(
            providers = listOf(ProviderConfig(id = "zai", kind = ProviderKind.ZAI, apiKey = "k")),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            serverUrl = "https://fuel.example.com",
            serverApiKey = "fd-key",
            agentSettings = AgentSettings(
                agents = listOf(
                    AgentConfig(id = "a1", name = "Coda", command = "letta-acp", args = "--yolo"),
                ),
            ),
            eventDropThresholdPct = 3.0,
            showHelp = true,
        )

        // Settings QR: keeps settings + prefs, drops agents.
        val settings = full.forSettingsQr()
        assertEquals(emptyList(), settings.agentSettings.agents)
        assertEquals(3.0, settings.eventDropThresholdPct)
        assertEquals(true, settings.showHelp)
        val restoredSettings = SettingsSyncData.fromQrData(settings.toQrData())
        assertEquals(SettingsSyncData.SCOPE_SETTINGS, restoredSettings?.scope)
        assertEquals(emptyList(), restoredSettings?.agentSettings?.agents)
        assertEquals(3.0, restoredSettings?.eventDropThresholdPct)

        // Agents QR: keeps full agent configs, nulls everything else.
        val agents = full.forAgentsQr()
        assertEquals("letta-acp", agents.agentSettings.agents.single().command)
        assertEquals(emptyList(), agents.providers)
        assertEquals(null, agents.serverUrl)
        assertEquals(null, agents.eventDropThresholdPct)
        val restoredAgents = SettingsSyncData.fromQrData(agents.toQrData())
        assertEquals(SettingsSyncData.SCOPE_AGENTS, restoredAgents?.scope)
        assertEquals("letta-acp", restoredAgents?.agentSettings?.agents?.single()?.command)
        assertEquals(emptyList(), restoredAgents?.providers)
    }

    @Test
    fun unsetNewFieldsDecodeAsNullFromLegacyV4Payload() {
        // A v4-era payload has none of the v5 fields — they must decode null,
        // leaving the receiver's own values untouched.
        val restored = SettingsSyncData.fromJson(
            """{"version":4,"providers":[],"themeMode":"SYSTEM","lightColorTheme":"Default","darkColorTheme":"Default"}""",
        )

        assertEquals(null, restored?.usageSources)
        assertEquals(null, restored?.eventDropThresholdPct)
        assertEquals(null, restored?.showHelp)
        assertEquals(null, restored?.showThemeIcon)
        assertEquals(null, restored?.feedbackUrl)
        assertEquals(null, restored?.feedbackRepo)
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
    fun settingsQrStaysReliablyScannable() {
        // The settings QR carries everything EXCEPT agents: providers with
        // realistic keys, connection, usage sources, section orders, prefs.
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
            // Worst case: user actually reordered Usage (Intel at default is
            // dropped entirely by from() — non-default orders are what ship).
            usageSectionOrder = listOf("waste", "metered", "drain"),
            usageSources = com.angussoftware.fueldashboard.usage.UsageSourcesSettings(
                letta = com.angussoftware.fueldashboard.usage.LettaSourceConfig(
                    enabled = true,
                    baseUrl = "https://api.letta.com",
                    apiKey = "sk-live-abc123def456ghi789jkl012mno345pqr678stu901vwx234yz",
                ),
            ),
            eventDropThresholdPct = 2.5,
            showHelp = false,
            showThemeIcon = true,
            feedbackUrl = "https://git.example.com",
            feedbackRepo = "acme/custom-repo",
        ).forSettingsQr()

        val qrData = syncData.toQrData()
        val version = minimumInformationDensityFor(qrData)

        // Lesson #84: versions ≤ 20 scan reliably off a screen. Hard bound.
        assertTrue(version <= 20, "Settings QR version $version exceeds the reliably-scannable bound (20); payload=${qrData.length} chars")
    }

    @Test
    fun agentsQrCarriesFullLauncherFidelityAndStaysScannable() {
        // The agents QR carries ONLY agents — at FULL launcher fidelity
        // (command/args/env ride this code, unlike the legacy combined QR).
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
        val syncData = SettingsSyncData(
            providers = emptyList(),
            themeMode = "SYSTEM",
            lightColorTheme = "Angus",
            darkColorTheme = "AngusDark",
            agentSettings = AgentSettings(agents = agents),
        ).forAgentsQr()

        // Scoped payloads are not slimmed — launcher fields survive the QR.
        val restored = SettingsSyncData.fromQrData(syncData.toQrData())
        assertEquals(
            "/home/rhomancer/.nvm/versions/node/v22.22.3/bin/letta-acp",
            restored?.agentSettings?.agents?.first()?.command,
        )

        val version = minimumInformationDensityFor(syncData.toQrData())
        assertTrue(version <= 20, "Agents QR version $version exceeds the reliably-scannable bound (20)")
    }

    @Test
    fun dormantProviderRoundTripsAndFalseCostsZeroQrBytes() {
        // dormant=true must survive a QR round-trip (synced receiver relies
        // on it), and dormant=false must add NOTHING to the QR payload
        // (encodeDefaults=false) — the QR version budget is tight.
        val base = SettingsSyncData(
            providers = listOf(
                ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k"),
            ),
        )
        val dormant = base.copy(
            providers = base.providers.map { it.copy(dormant = true) },
        )

        val restoredDormant = SettingsSyncData.fromQrData(dormant.toQrData())
        assertTrue(restoredDormant?.providers?.first()?.dormant == true, "dormant=true must survive QR round-trip")

        val restoredBase = SettingsSyncData.fromQrData(base.toQrData())
        assertTrue(restoredBase?.providers?.first()?.dormant == false, "absent field defaults to dormant=false")

        // Byte-neutrality: decompressed JSON with dormant=false must not
        // contain the key at all (default omitted).
        val json = Base45.decode(base.toQrData())?.let { decompress(it) }
        assertNotNull(json)
        assertFalse(json.contains("\"dormant\""), "dormant=false must not be serialized into QR payloads: $json")
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