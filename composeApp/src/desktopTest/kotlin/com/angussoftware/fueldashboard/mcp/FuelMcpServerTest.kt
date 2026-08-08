package com.angussoftware.fueldashboard.mcp

import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelMcpServerTest {
    @Test
    fun addProviderAppendsToExistingSettings() {
        val existing = ProviderConfig(
            id = "existing",
            kind = ProviderKind.ZAI,
            apiKey = "existing-key",
        )
        val added = ProviderConfig(
            id = "added",
            kind = ProviderKind.OPENAI,
            apiKey = "new-key",
        )

        val result = addProviderToSettings(MultiProviderSettings(listOf(existing)), added)

        assertEquals(listOf(existing, added), result.providers)
    }

    @Test
    fun removeProviderUsesIdBeforeName() {
        val byId = ProviderConfig(
            id = "target",
            kind = ProviderKind.OPENAI,
            displayName = "Other provider",
        )
        val byName = ProviderConfig(
            id = "other",
            kind = ProviderKind.ANTHROPIC,
            displayName = "Target provider",
        )

        val result = removeProviderFromSettings(
            settings = MultiProviderSettings(listOf(byId, byName)),
            id = "target",
            name = "Target provider",
        )

        assertEquals(byId, result.removed)
        assertEquals(listOf(byName), result.settings.providers)
    }

    @Test
    fun removeProviderByNameIsCaseInsensitiveAndReportsNotFound() {
        val provider = ProviderConfig(
            id = "named",
            kind = ProviderKind.OPENAI,
            displayName = "Personal OpenAI",
        )
        val settings = MultiProviderSettings(listOf(provider))

        val removed = removeProviderFromSettings(settings, id = null, name = "personal openai")
        val missing = removeProviderFromSettings(settings, id = "missing", name = null)

        assertEquals(provider, removed.removed)
        assertEquals(emptyList(), removed.settings.providers)
        assertNull(missing.removed)
        assertEquals(settings, missing.settings)
    }

    @Test
    fun safeProviderSummariesDoNotContainApiKeys() {
        val summary = safeProviders(
            MultiProviderSettings(
                listOf(
                    ProviderConfig(
                        id = "openai",
                        kind = ProviderKind.OPENAI,
                        apiKey = "secret-key",
                        displayName = "Work OpenAI",
                        serverUrl = "https://api.openai.com",
                    ),
                ),
            ),
        ).single()

        assertEquals("openai", summary.id)
        assertEquals("OPENAI", summary.kind)
        assertEquals("Work OpenAI", summary.name)
        assertEquals("https://api.openai.com", summary.serverUrl)
    }
}