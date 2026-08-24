package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip test: [DashboardSnapshot.build] output (producer, desktop/server side)
 * must parse cleanly through [RemoteDashboardFetcher.parse] (consumer, mobile side).
 *
 * This is the connected-mode data contract: desktop serializes its full display
 * state to JSON, mobile fetches `/dashboard` and maps it back into the same
 * display types. Any drift between the two sides silently blanks mobile's
 * Usage/Intel tabs — this test pins the contract.
 */
class SnapshotRoundTripTest {

    private fun fullState(): DashboardState = DashboardState(
        lastUpdated = 1_760_000_000_000L,
        burnRate = 7.5,
        settings = MultiProviderSettings(
            providers = listOf(
                ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k"),
            ),
        ),
        providerReports = mapOf(
            "zai-1" to ProviderReport(
                providerId = "zai-1",
                displayName = "z.ai",
                type = ProviderType.WINDOW_CREDIT,
                remainingPct = 58,
                resetsAt = 1_760_000_100_000L,
                windowHours = 5.0,
                windows = listOf(
                    ReportWindow("5h Token Window", 58, 1_760_000_100_000L, 5.0),
                    ReportWindow("Session", 90, 1_760_000_100_000L, 5.0),
                ),
            ),
        ),
        meteredBySource24h = listOf(MeteredUsageDisplay("Coda", 1000, 100, 5, 42.0)),
        meteredByModel24h = listOf(MeteredUsageDisplay("glm-5.2", 800, 80, 4)),
        meteredBySource7d = listOf(MeteredUsageDisplay("Coda", 7000, 700, 35, 294.0)),
        meteredByModel7d = listOf(MeteredUsageDisplay("glm-5.2", 5600, 560, 28)),
        meteredByConversation24h = listOf(
            ConversationUsageDisplay(
                conversationId = "conv-1",
                agentName = "Coda",
                model = "glm-5.2",
                inputTokens = 600,
                outputTokens = 60,
                requestCount = 3,
                creditCost = 25.2,
                title = "Fuel dashboard work",
            ),
        ),
        meteredByAgentModel24h = listOf(
            AgentModelUsageDisplay("Coda", "glm-5.2", 600, 60, 3),
        ),
        wasteByProvider = listOf(
            FuelIntelligence.ProviderWaste(
                providerId = "zai-1",
                providerName = "z.ai",
                windowMs = 5 * 3_600_000L,
                daily = listOf(
                    FuelIntelligence.DailyWaste(
                        dayStart = 1_750_000_000_000L,
                        windows = 5,
                        observed = 5,
                        estimated = 0,
                        wastedPctAvg = 62.0,
                        anyExhausted = true,
                    ),
                ),
                wastedPctAvg = 62.0,
            ),
        ),
        fuelEvents = listOf(
            FuelIntelligence.FuelEvent(2_000L, FuelIntelligence.FuelEventType.FUEL_DROP, "Fuel dropped 4.0%"),
            FuelIntelligence.FuelEvent(3_000L, FuelIntelligence.FuelEventType.MODEL_SWITCH, "Switched to glm-4.7"),
        ),
    )

    @Test
    fun roundTrip_meteredUsagePreserved() {
        val json = DashboardSnapshot.build(fullState()).toString()
        val parsed = RemoteDashboardFetcher.parse(json)

        val metered = assertNotNull(parsed?.metered, "metered windows lost in round-trip")

        assertEquals(1, metered.bySource24h.size)
        val src = metered.bySource24h.first()
        assertEquals("Coda", src.label)
        assertEquals(1000L, src.inputTokens)
        assertEquals(100L, src.outputTokens)
        assertEquals(5L, src.requestCount)
        assertEquals(42.0, src.creditCost)

        assertEquals(1, metered.byModel24h.size)
        assertEquals("glm-5.2", metered.byModel24h.first().label)

        assertEquals(1, metered.bySource7d.size)
        assertEquals(7000L, metered.bySource7d.first().inputTokens)

        assertEquals(1, metered.byModel7d.size)

        val conv = metered.byConversation24h.single()
        assertEquals("conv-1", conv.conversationId)
        assertEquals("Coda", conv.agentName)
        assertEquals("Fuel dashboard work", conv.title)
        assertEquals(25.2, conv.creditCost)

        assertEquals(1, metered.byAgentModel24h.size)
        assertEquals("Coda", metered.byAgentModel24h.first().agentName)
    }

    @Test
    fun roundTrip_intelligencePreserved() {
        val json = DashboardSnapshot.build(fullState()).toString()
        val snap = RemoteDashboardFetcher.parse(json)

        val intel = assertNotNull(snap?.intelligence, "intelligence lost in round-trip")

        val waste = intel.wasteByProvider.single()
        assertEquals("zai-1", waste.providerId)
        assertEquals("z.ai", waste.providerName)
        assertEquals(5 * 3_600_000L, waste.windowMs)
        assertEquals(62.0, waste.wastedPctAvg)
        val day = waste.daily.single()
        assertEquals(1_750_000_000_000L, day.dayStart)
        assertEquals(5, day.windows)
        assertTrue(day.anyExhausted)

        assertEquals(2, intel.fuelEvents.size)
        // Parser returns events newest-first (sortedByDescending timestamp) —
        // the display convention on both platforms.
        val newest = intel.fuelEvents.first()
        assertEquals(FuelIntelligence.FuelEventType.MODEL_SWITCH, newest.type)
        assertEquals("Switched to glm-4.7", newest.description)
        assertEquals(FuelIntelligence.FuelEventType.FUEL_DROP, intel.fuelEvents[1].type)
        assertEquals("Fuel dropped 4.0%", intel.fuelEvents[1].description)
    }

    @Test
    fun roundTrip_emptyStateParsesCleanly() {
        // A fresh install serializes an empty dashboard — mobile must parse
        // it without errors (sections empty, not null-crash).
        val json = DashboardSnapshot.build(DashboardState()).toString()
        val parsed = assertNotNull(RemoteDashboardFetcher.parse(json))

        // usage section is always present in the snapshot; with no data the
        // windows map to empty lists, not null
        val metered = parsed.metered
        if (metered != null) {
            assertTrue(metered.bySource24h.isEmpty())
            assertTrue(metered.byModel24h.isEmpty())
        }
        val intel = parsed.intelligence
        if (intel != null) {
            assertTrue(intel.wasteByProvider.isEmpty())
            assertTrue(intel.fuelEvents.isEmpty())
        }
    }

    @Test
    fun roundTrip_garbageBodyReturnsNull() {
        assertNull(RemoteDashboardFetcher.parse("not json at all {"))
        assertNull(RemoteDashboardFetcher.parse("\"just a string\""))
    }
}
