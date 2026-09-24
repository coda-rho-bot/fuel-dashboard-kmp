package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matching Claude Code's configured endpoint back to a provider the user has
 * set up, so the dashboard can say which gauge the sessions are burning.
 */
class ClaudeCodeRouteTest {

    private val zai = ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k")
    private val anthropic = ProviderConfig(id = "ant-1", kind = ProviderKind.ANTHROPIC, apiKey = "k")
    private val providers = listOf(zai, anthropic)

    @Test
    fun absentBaseUrlMeansStockAnthropic() {
        // Claude Code says "default endpoint" by omitting the variable, so an
        // absent value is a positive answer, not a missing one.
        val route = ClaudeCodeRoute(baseUrl = null)
        assertTrue(route.isDefaultAnthropic)
        assertEquals("Anthropic", route.label(providers))
        assertNull(route.host)
    }

    @Test
    fun blankBaseUrlIsAlsoStockAnthropic() {
        assertTrue(ClaudeCodeRoute(baseUrl = "   ").isDefaultAnthropic)
    }

    @Test
    fun defaultAnthropicMatchesTheSubscriptionProvider() {
        // The common case: no ANTHROPIC_BASE_URL means Claude Code is on
        // Anthropic's own endpoint, which for a Max session is the plan. The
        // subscription provider is what is being burned, so it is what gets
        // marked in use.
        val plan = ProviderConfig(id = "cc-1", kind = ProviderKind.CLAUDE_CODE)
        assertEquals("cc-1", ClaudeCodeRoute.matchProvider(null, listOf(zai, plan)))
    }

    @Test
    fun defaultAnthropicFallsBackToAnApiProviderWhenNoPlanIsConfigured() {
        assertEquals("ant-1", ClaudeCodeRoute.matchProvider(null, providers))
    }

    @Test
    fun defaultAnthropicMatchesNothingWhenNeitherIsConfigured() {
        assertNull(ClaudeCodeRoute.matchProvider(null, listOf(zai)))
    }

    @Test
    fun matchesOnHostBecauseTheRoutingUrlCarriesAnExtraPath() {
        // The crux: z.ai's Anthropic-compatible endpoint is
        // https://api.z.ai/api/anthropic while the provider is configured as
        // https://api.z.ai. Whole-string comparison would never match.
        val id = ClaudeCodeRoute.matchProvider("https://api.z.ai/api/anthropic", providers)
        assertEquals("zai-1", id)
    }

    @Test
    fun matchedProviderSuppliesTheLabel() {
        val route = ClaudeCodeRoute(
            baseUrl = "https://api.z.ai/api/anthropic",
            matchedProviderId = "zai-1",
        )
        assertFalse(route.isDefaultAnthropic)
        assertEquals(zai.resolvedDisplayName(), route.label(providers))
    }

    @Test
    fun unmatchedEndpointStillReportsItsHostRatherThanGivingUp() {
        val route = ClaudeCodeRoute(baseUrl = "https://proxy.internal:8443/v1")
        assertNull(ClaudeCodeRoute.matchProvider(route.baseUrl, providers))
        assertEquals("proxy.internal", route.host)
        assertEquals("proxy.internal", route.label(providers))
    }

    @Test
    fun aRenamedProviderStillMatches() {
        // Matching is on host, so a custom display name cannot break it.
        val renamed = listOf(zai.copy(displayName = "My Coding Plan"))
        assertEquals("zai-1", ClaudeCodeRoute.matchProvider("https://api.z.ai/api/anthropic", renamed))
        assertEquals(
            "My Coding Plan",
            ClaudeCodeRoute(baseUrl = "https://api.z.ai/x", matchedProviderId = "zai-1").label(renamed),
        )
    }

    @Test
    fun hostParsingHandlesTheAwkwardForms() {
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("https://api.z.ai/api/anthropic"))
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("HTTPS://API.Z.AI"))
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("https://api.z.ai:8443/v1"))
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("https://user:pw@api.z.ai/v1"))
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("  https://api.z.ai/  "))
        assertEquals("api.z.ai", ClaudeCodeRoute.hostOf("api.z.ai/api/anthropic"), "scheme is optional")
        // Malformed input must degrade to null, never throw: this string comes
        // from a hand-editable settings file.
        assertNull(ClaudeCodeRoute.hostOf(""))
        assertNull(ClaudeCodeRoute.hostOf("   "))
        assertNull(ClaudeCodeRoute.hostOf("https://"))
    }

    @Test
    fun noProvidersConfiguredMatchesNothingWithoutThrowing() {
        assertNull(ClaudeCodeRoute.matchProvider("https://api.z.ai", emptyList()))
        assertNull(ClaudeCodeRoute.matchProvider(null, emptyList()))
    }

    @Test
    fun permissionModeIsCarriedThrough() {
        // It commonly changes with the endpoint, so it belongs with the route.
        assertEquals(
            "bypassPermissions",
            ClaudeCodeRoute(baseUrl = "https://api.z.ai", permissionMode = "bypassPermissions")
                .permissionMode,
        )
    }
}
