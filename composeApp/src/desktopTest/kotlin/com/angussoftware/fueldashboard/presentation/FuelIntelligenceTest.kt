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
    // Waste windows
    // ------------------------------------------------------------------

    @Test
    fun wasteWindowFlagsDropWithoutMeteredUsage() {
        val h0 = 1_000_000_000_000L / hour * hour
        val snaps = listOf(
            snapshot(h0 + 0, 80.0),
            snapshot(h0 + minute(10), 75.0), // -5% consumption
            snapshot(h0 + minute(20), 75.0),
        )
        val windows = FuelIntelligence.wasteWindows(snaps, usage = emptyList(), hours = 24)
        assertEquals(1, windows.size)
        val w = windows.first()
        assertEquals(5.0, w.fuelConsumedPct, 0.001)
        assertEquals(0L, w.meteredTokens)
        assertTrue(w.unattributed)
    }

    @Test
    fun wasteWindowDoesNotFlagDropWithMeteredUsage() {
        val h0 = 1_000_000_000_000L / hour * hour
        val snaps = listOf(
            snapshot(h0 + 0, 80.0),
            snapshot(h0 + minute(10), 75.0), // -5%
        )
        val usage = listOf(usage(h0 + minute(5), 50_000))
        val windows = FuelIntelligence.wasteWindows(snaps, usage, hours = 24)
        assertEquals(1, windows.size)
        assertTrue(!windows.first().unattributed)
        assertEquals(50_000L, windows.first().meteredTokens)
    }

    @Test
    fun wasteWindowIgnoresRisesAndTinyDrops() {
        val h0 = 1_000_000_000_000L / hour * hour
        val snaps = listOf(
            snapshot(h0 + 0, 80.0),
            snapshot(h0 + minute(10), 90.0), // rise (window expiry) — not consumption
            snapshot(h0 + minute(20), 89.8), // -0.2% below threshold
        )
        val windows = FuelIntelligence.wasteWindows(snaps, usage = emptyList(), hours = 24)
        // 89.8 bucket: 0.2 consumed, but below 1.0 threshold → window exists, unattributed=false
        assertEquals(1, windows.size)
        assertTrue(!windows.first().unattributed)
        assertEquals(0.2, windows.first().fuelConsumedPct, 0.001)
    }

    @Test
    fun wasteWindowsBucketsByHour() {
        val h0 = 1_000_000_000_000L / hour * hour
        val snaps = listOf(
            snapshot(h0 + minute(59), 80.0),
            snapshot(h0 + hour + minute(1), 70.0), // bucket 2: -10%
            snapshot(h0 + hour + minute(30), 68.0), // bucket 2: -2% (total 12)
            snapshot(h0 + 2 * hour, 60.0), // bucket 3: -8%
        )
        val windows = FuelIntelligence.wasteWindows(snaps, usage = emptyList(), hours = 24)
        assertEquals(2, windows.size)
        assertEquals(12.0, windows[0].fuelConsumedPct, 0.001)
        assertEquals(8.0, windows[1].fuelConsumedPct, 0.001)
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
