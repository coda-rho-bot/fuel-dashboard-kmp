package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.SettingsSyncData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scope-matrix test for [FuelViewModel.importSyncedSettings].
 *
 * QR sync payloads are scoped by domain so each QR only applies its own
 * fields (task_33/QR-split design):
 * - SCOPE_SETTINGS applies everything EXCEPT agent configs
 * - SCOPE_AGENTS applies ONLY agent configs
 * - SCOPE_FULL (and legacy payloads with no scope field) applies both
 *
 * Regression risk this pins: a scoped payload clobbering the other domain
 * (e.g. scanning the agents QR wiping provider configuration), and the
 * Remote Dashboard (CONNECTED_API) provider being duplicated on repeated
 * imports instead of replaced.
 *
 * Test JVM runs with an isolated `user.home` (see build.gradle.kts), so the
 * backing Preferences node starts empty — no real provider configs leak in
 * from the dev machine, and imports never start live polling (imported
 * providers here are intentionally unconfigured: blank API keys).
 */
class ImportScopeMatrixTest {

    private lateinit var vm: FuelViewModel

    @BeforeTest
    fun setUp() {
        // Test-JVM prefs persist across test methods (isolated user.home only
        // isolates the JVM from the dev machine, not test-from-test). Each
        // scope test must start from a clean store — and the VM must be
        // constructed AFTER the clear, since its init loads settings eagerly.
        val node: java.util.prefs.Preferences = java.util.prefs.Preferences.userRoot().node("fuel-dashboard")
        node.clear()
        vm = FuelViewModel()
    }

    private fun payload(
        scope: String,
        providers: List<ProviderConfig>,
        agents: List<AgentConfig>,
        serverUrl: String? = null,
    ) = SettingsSyncData(
        scope = scope,
        providers = providers,
        themeMode = "SYSTEM",
        lightColorTheme = "Default",
        darkColorTheme = "Default",
        serverUrl = serverUrl,
        serverApiKey = serverUrl?.let { "server-key" },
        agentSettings = AgentSettings(agents = agents),
    )

    private fun unconfiguredProvider(id: String) =
        ProviderConfig(id = id, kind = ProviderKind.ZAI, apiKey = "")

    // ── SCOPE_SETTINGS: everything except agents ──────────────────────

    @Test
    fun settingsScope_appliesProvidersAndConnection_butNotAgents() {
        vm.importSyncedSettings(
            payload(
                scope = SettingsSyncData.SCOPE_SETTINGS,
                providers = listOf(unconfiguredProvider("settings-p1")),
                agents = listOf(AgentConfig("agent-settings-1", "SettingsAgent", "/bin/true")),
                serverUrl = "http://192.168.1.10:8322",
            ),
        )

        val state = vm.state.value

        // Providers applied + Remote Dashboard connection injected
        assertTrue(
            state.settings.providers.any { it.id == "settings-p1" },
            "settings-scope must apply providers",
        )
        val connected = state.settings.providers.filter { it.kind == ProviderKind.CONNECTED_API }
        assertEquals(1, connected.size, "settings-scope with serverUrl must add exactly one CONNECTED_API")
        assertEquals("http://192.168.1.10:8322", connected.single().serverUrl)
        assertEquals("server-key", connected.single().apiKey)

        // Agents NOT applied — and no synced display entry materialized
        assertFalse(
            state.agentSettings.agents.any { it.id == "agent-settings-1" },
            "settings-scope must NOT apply agent configs",
        )
        assertFalse(
            state.acpAgents.any { it.id == "agent-settings-1" },
            "settings-scope must NOT materialize synced agent entries",
        )
    }

    // ── SCOPE_AGENTS: only agent configs ──────────────────────────────

    @Test
    fun agentsScope_appliesAgentsOnly_providersUntouched() {
        val before = vm.state.value.settings.providers

        vm.importSyncedSettings(
            payload(
                scope = SettingsSyncData.SCOPE_AGENTS,
                providers = listOf(unconfiguredProvider("agents-p1")),
                agents = listOf(AgentConfig("agent-agents-1", "FleetAgent", "/usr/bin/letta-acp")),
                serverUrl = "http://192.168.1.10:8322",
            ),
        )

        val state = vm.state.value

        // Agents applied + materialized as synced display entries (task_30)
        assertTrue(
            state.agentSettings.agents.any { it.id == "agent-agents-1" },
            "agents-scope must apply agent configs",
        )
        val synced = state.acpAgents.firstOrNull { it.id == "agent-agents-1" }
        assertTrue(synced != null, "agents-scope must materialize a display entry")
        assertEquals("synced", synced.status)
        assertEquals("FleetAgent", synced.name)

        // Providers NOT applied — not even the CONNECTED_API injection
        assertEquals(
            before.map { it.id },
            state.settings.providers.map { it.id },
            "agents-scope must leave providers untouched",
        )
        assertFalse(
            state.settings.providers.any { it.kind == ProviderKind.CONNECTED_API },
            "agents-scope must NOT inject a Remote Dashboard provider",
        )
    }

    // ── SCOPE_FULL (default/legacy): both domains ─────────────────────

    @Test
    fun fullScope_appliesBothDomains() {
        vm.importSyncedSettings(
            payload(
                scope = SettingsSyncData.SCOPE_FULL,
                providers = listOf(unconfiguredProvider("full-p1")),
                agents = listOf(AgentConfig("agent-full-1", "FullAgent", "/bin/true")),
                serverUrl = "https://fuel.example.com",
            ),
        )

        val state = vm.state.value
        assertTrue(state.settings.providers.any { it.id == "full-p1" })
        assertTrue(state.settings.providers.any { it.kind == ProviderKind.CONNECTED_API })
        assertTrue(state.agentSettings.agents.any { it.id == "agent-full-1" })
        assertTrue(state.acpAgents.any { it.id == "agent-full-1" && it.status == "synced" })
    }

    @Test
    fun legacyPayload_withoutScopeField_behavesAsFull() {
        // Payloads serialized before the scope field existed deserialize with
        // the SCOPE_FULL default — must keep applying both domains.
        vm.importSyncedSettings(
            payload(
                scope = SettingsSyncData.SCOPE_FULL,
                providers = listOf(unconfiguredProvider("legacy-p1")),
                agents = listOf(AgentConfig("agent-legacy-1", "LegacyAgent", "/bin/true")),
            ),
        )

        val state = vm.state.value
        assertTrue(state.settings.providers.any { it.id == "legacy-p1" })
        assertTrue(state.agentSettings.agents.any { it.id == "agent-legacy-1" })
    }

    // ── Re-import stability ───────────────────────────────────────────

    @Test
    fun repeatedFullImport_replacesRemoteDashboardProvider_noDuplicates() {
        val p = payload(
            scope = SettingsSyncData.SCOPE_FULL,
            providers = listOf(unconfiguredProvider("rep-p1")),
            agents = emptyList(),
            serverUrl = "http://10.0.0.5:8322",
        )

        vm.importSyncedSettings(p)
        vm.importSyncedSettings(p.copy(serverApiKey = "rotated-key"))
        vm.importSyncedSettings(p.copy(serverApiKey = "rotated-again"))

        val connected = vm.state.value.settings.providers.filter { it.kind == ProviderKind.CONNECTED_API }
        assertEquals(1, connected.size, "repeated imports must replace, not duplicate, the Remote Dashboard provider")
        assertEquals("rotated-again", connected.single().apiKey)
        assertEquals(2, vm.state.value.settings.providers.size, "imported provider + remote dashboard, nothing else")
    }
}
