package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuelIntelligenceTest {

    private fun snapshot(
        ts: Long,
        pct: Double?,
        agents: Int = 0,
        models: String? = null,
    ) = FuelSnapshotRecord(
        id = 0L,
        timestamp = ts,
        tokensPct = pct,
        sessionPct = null,
        activeAgentCount = agents,
        activeModels = models,
        resetAt = null,
    )

    private fun usage(ts: Long, tokens: Long) = UsageRecord(
        id = 0L,
        timestamp = ts,
        source = "coda",
        model = "glm-5.2",
        conversationId = null,
        inputTokens = tokens,
        outputTokens = 0L,
        requestCount = 1L,
    )

    private val hour = 3_600_000L

    // ------------------------------------------------------------------
    // Expired-quota waste (provider-aware)
    // ------------------------------------------------------------------

    private fun psnap(ts: Long, pct: Double, windowHours: Double = 5.0, provider: String = "zai") =
        com.angussoftware.fueldashboard.database.ProviderFuelSnapshot(
            id = 0L, timestamp = ts, providerId = provider, providerName = provider,
            providerType = "WINDOW_CREDIT", remainingPct = pct, resetAt = null, windowHours = windowHours,
        )

    @Test
    fun unusedWindowIsFullyWasted() {
        // 5h window, gauge stays at 100% remaining the whole time
        val t0 = 1_000_000_000_000L
        val snaps = (0..3).map { h ->
            psnap(t0 + h * 5 * hour, 100.0)
        }
        val waste = FuelIntelligence.providerWaste(snaps, since = t0, now = t0 + 4 * 5 * hour)
        assertEquals(1, waste.size)
        assertEquals(100.0, waste.first().wastedPctAvg, 0.01)
    }

    @Test
    fun windowEndingAtTenPercentWastesTen() {
        val t0 = 1_000_000_000_000L
        // boundary samples: 90% used at each expiry → 10% remaining wasted
        val snaps = (1..3).map { k -> psnap(t0 + k * 5 * hour, 10.0) }
        val waste = FuelIntelligence.providerWaste(snaps, since = t0, now = t0 + 3 * 5 * hour)
        assertEquals(10.0, waste.first().wastedPctAvg, 0.01)
    }

    @Test
    fun exhaustedWindowsWasteNothing() {
        val t0 = 1_000_000_000_000L
        val snaps = (1..3).map { k -> psnap(t0 + k * 5 * hour, 0.0) }
        val waste = FuelIntelligence.providerWaste(snaps, since = t0, now = t0 + 3 * 5 * hour)
        assertEquals(0.0, waste.first().wastedPctAvg, 0.01)
        assertTrue(waste.first().daily.all { it.anyExhausted })
    }

    @Test
    fun providerWindowLengthComesFromItsOwnMetadata() {
        val t0 = 1_000_000_000_000L
        // zai: 5h windows; letta-daily: 24h windows — same timeline
        val zai = (1..2).map { k -> psnap(t0 + k * 5 * hour, 50.0, windowHours = 5.0, provider = "zai") }
        val letta = listOf(
            psnap(t0, 90.0, windowHours = 24.0, provider = "letta"),
            psnap(t0 + 24 * hour, 80.0, windowHours = 24.0, provider = "letta"),
        )
        val waste = FuelIntelligence.providerWaste(zai + letta, since = t0, now = t0 + 24 * hour)
        assertEquals(2, waste.size)
        val z = waste.first { it.providerId == "zai" }
        val l = waste.first { it.providerId == "letta" }
        assertEquals(5 * hour, z.windowMs)
        assertEquals(24 * hour, l.windowMs)
        assertEquals(1, l.daily.first().windows) // one 24h window observed
    }

    // ------------------------------------------------------------------
    // Fuel events
    // ------------------------------------------------------------------

    @Test
    fun fuelDropEventsAggregateBursts() {
        val t0 = 2_000_000_000_000L
        val snaps = listOf(
            snapshot(t0, 90.0),
            snapshot(t0 + minute(1), 88.0, agents = 3, models = "glm-5.2"), // -2 (context: 3 agents active)
            snapshot(t0 + minute(2), 85.0), // -3 (burst total 5)
            snapshot(t0 + minute(45), 85.0), // gap — flush
        )
        val events = FuelIntelligence.fuelEvents(
            snaps,
            modelPeriods = emptyList(),
            decisions = emptyList(),
            dropThresholdPct = 3.0,
        )
        val drops = events.filter { it.type == FuelIntelligence.FuelEventType.FUEL_DROP }
        assertEquals(1, drops.size)
        assertEquals(5.0, parseDropPct(drops.first().description), 0.001)
        assertTrue(drops.first().description.contains("3 active agents"))
        assertTrue(drops.first().description.contains("glm-5.2"))
    }

    @Test
    fun separateDropsBecomeSeparateEvents() {
        val t0 = 2_000_000_000_000L
        val snaps = listOf(
            snapshot(t0, 90.0),
            snapshot(t0 + minute(1), 85.0), // -5 → event 1
            snapshot(t0 + minute(30), 85.0), // gap flushes
            snapshot(t0 + minute(31), 80.0), // -5 → event 2
            snapshot(t0 + minute(60), 80.0),
        )
        val events = FuelIntelligence.fuelEvents(
            snaps,
            modelPeriods = emptyList(),
            decisions = emptyList(),
        )
        assertEquals(2, events.count { it.type == FuelIntelligence.FuelEventType.FUEL_DROP })
    }

    @Test
    fun modelSwitchesDetected() {
        val periods = listOf(
            FuelIntelligence.AgentModelPeriod("Beacon", "glm-5.2", 100L, 500L),
            FuelIntelligence.AgentModelPeriod("Beacon", "glm-4.7", 500L, null),
            FuelIntelligence.AgentModelPeriod("Coda", "glm-5.2", 100L, null),
        )
        val events = FuelIntelligence.fuelEvents(
            snapshots = emptyList(),
            modelPeriods = periods,
            decisions = emptyList(),
        )
        val switches = events.filter { it.type == FuelIntelligence.FuelEventType.MODEL_SWITCH }
        assertEquals(1, switches.size)
        assertEquals(500L, switches.first().timestamp)
        assertTrue(switches.first().description.contains("Beacon"))
        assertTrue(switches.first().description.contains("glm-5.2 → glm-4.7"))
    }

    @Test
    fun decisionsBecomeRecommendationEventsAndTimelineSortsNewestFirst() {
        val decisions = listOf(
            FuelIntelligence.DecisionRecord(1_000L, "glm-4.7", "cheapest with headroom"),
        )
        val periods = listOf(
            FuelIntelligence.AgentModelPeriod("Beacon", "glm-5.2", 900L, 2_000L),
            FuelIntelligence.AgentModelPeriod("Beacon", "glm-4.7", 2_000L, null),
        )
        val snaps = listOf(
            snapshot(3_000L, 90.0),
            snapshot(3_100L, 80.0), // -10 drop at ts 3100
            snapshot(3_200L, 80.0),
        )
        val events = FuelIntelligence.fuelEvents(snaps, periods, decisions)
        assertEquals(3, events.size)
        // Newest first
        assertTrue(events[0].timestamp >= events[1].timestamp && events[1].timestamp >= events[2].timestamp)
        assertTrue(events.any { it.type == FuelIntelligence.FuelEventType.RECOMMENDATION && it.description.contains("glm-4.7") })
    }

    private fun minute(m: Long) = m * 60_000L

    /** Extracts the drop percentage from "Fuel dropped 5.0% ..." descriptions. */
    private fun parseDropPct(description: String): Double =
        Regex("dropped ([0-9.]+)%").find(description)!!.groupValues[1].toDouble()
}
