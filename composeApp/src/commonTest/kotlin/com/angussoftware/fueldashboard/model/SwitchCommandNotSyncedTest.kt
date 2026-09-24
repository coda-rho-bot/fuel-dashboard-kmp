package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.settings.ThemeController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider's switch command must never cross a machine boundary.
 *
 * It names a command this app will later execute by itself, so accepting one
 * from a synced payload would make "import settings" — a QR scan, or a
 * POST /sync — a path to arbitrary code execution on the importer. Both ends
 * drop it: the sender does not ship it, and the receiver does not trust it.
 */
class SwitchCommandNotSyncedTest {

    private val armed = ProviderConfig(
        id = "zai-1",
        kind = ProviderKind.ZAI,
        apiKey = "k",
        activateCommand = "switch-provider backup",
        swapAwayBelowPct = 15,
    )

    private fun payload() = SettingsSyncData.from(
        settings = MultiProviderSettings(providers = listOf(armed)),
        agentSettings = AgentSettings(),
        themeController = ThemeController,
    )

    @Test
    fun exportedPayloadCarriesNoSwitchCommand() {
        val exported = payload().providers.single()

        assertEquals("", exported.activateCommand)
        assertEquals(0, exported.swapAwayBelowPct)
        // Everything else must still travel — this strips one field, not the config.
        assertEquals("zai-1", exported.id)
        assertEquals("k", exported.apiKey)
        assertEquals(ProviderKind.ZAI, exported.kind)
    }

    @Test
    fun serializedPayloadDoesNotContainTheCommandString() {
        val json = payload().toJson()

        assertTrue(
            "switch-provider" !in json,
            "the command leaked into the sync payload: $json",
        )
    }
}
