package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord
import kotlinx.datetime.toLocalDateTime
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
    fun unobservedWindowsReconstructedFromMeteredUsage() {
        // Two observed 5h windows calibrate capacity; a third window passes
        // with the dashboard DOWN (no snapshots) but metered usage exists.
        val t0 = 1_000_000_000_000L
        val snaps = listOf(
            psnap(t0, 100.0),
            psnap(t0 + 5 * hour, 50.0),           // window 1: 50% used
            psnap(t0 + 6 * hour, 100.0),
            psnap(t0 + 10 * hour, 50.0),          // window 2: 50% used
            // dashboard down 10h..15h — window 3 unobserved
            psnap(t0 + 15 * hour + minute(10), 100.0),
        )
        // Calibration: 50% used <-> 1000 tokens = 20 tokens per 1%
        val usage = listOf(
            usage(t0 + 4 * hour, 1000),
            usage(t0 + 9 * hour, 1000),
            usage(t0 + 14 * hour, 1000), // unobserved window's usage
        )
        val waste = FuelIntelligence.providerWaste(
            snapshots = snaps, usage = usage,
            since = t0, now = t0 + 15 * hour + minute(10),
        )
        assertEquals(1, waste.size)
        val obs = waste.first().daily.sumOf { it.observed }
        val est = waste.first().daily.sumOf { it.estimated }
        assertTrue(obs >= 2, "expected >=2 observed, got $obs")
        assertEquals(1, est, "expected 1 reconstructed, got $est (daily=${waste.first().daily})")
        // Reconstructed: 1000 tokens / 20 per-% = 50% used -> 50% wasted.
        // Verify via the tiles: average across 3 windows (50+50+50)/3 = 50
        assertEquals(50.0, waste.first().wastedPctAvg, 15.0)
    }

    @Test
    fun noCalibrationMeansNoReconstruction() {
        val t0 = 1_000_000_000_000L
        val snaps = listOf(
            psnap(t0, 100.0),
            psnap(t0 + 5 * hour, 50.0),
            psnap(t0 + 15 * hour, 100.0), // long gap, unobserved windows
        )
        val usage = listOf(usage(t0 + 4 * hour, 1000))
        val waste = FuelIntelligence.providerWaste(
            snapshots = snaps, usage = usage,
            since = t0, now = t0 + 15 * hour,
        )
        assertEquals(0, waste.first().daily.sumOf { it.estimated })
    }

    @Test
    fun fixedResetGapReconstructionAlignsWithMeasuredResets() {
        // Regression (adversarial review): reconstruction boundaries used to be
        // a grid anchored at dataStart, but fixed-reset measured tiles end at
        // ACTUAL resetAt times — the grid fell beside measured windows and
        // fabricated phantom tiles. Expected here: 2 measured (r1, r2) + 2
        // genuinely-missing gap windows (r3, r4) reconstructed, nothing else.
        val t0 = 1_000_000_000_000L
        val r1 = t0 + 12 * hour
        val r2 = r1 + 24 * hour
        val r3 = r2 + 24 * hour
        val r4 = r3 + 24 * hour
        val r5 = r4 + 24 * hour
        fun letta(ts: Long, pct: Double, reset: Long) =
            psnap(ts, pct, windowHours = 24.0, provider = "letta").copy(resetAt = reset)
        val snaps = listOf(
            letta(t0, 100.0, r1),
            letta(t0 + 2 * hour, 60.0, r1),   // 40% used
            letta(t0 + 11 * hour, 50.0, r1),   // -> measured tile at r1: 50% wasted
            letta(r1 + minute(5), 100.0, r2),
            letta(t0 + 20 * hour, 80.0, r2),  // 20% used
            letta(t0 + 23 * hour, 70.0, r2),   // -> measured tile at r2: 70% wasted
            // dashboard down t0+23h .. r4+5min — resets r3, r4 unobserved
            letta(r4 + minute(5), 90.0, r5),  // resetAt jumps r2 -> r5
        )
        // Calibration: r1 window 2000 tok / 50% used, r2 window 1200 tok / 30%
        // used → 40 tokens per 1%. Gap usage: 400 tok (r3 window) → 90% wasted,
        // 800 tok (r4 window) → 80% wasted.
        val usage = listOf(
            usage(t0 + 2 * hour, 2000),
            usage(t0 + 20 * hour, 1200),
            usage(r2 + 12 * hour, 400),
            usage(r3 + 12 * hour, 800),
        )
        val waste = FuelIntelligence.providerWaste(
            snapshots = snaps, usage = usage, usageOwnerProviderId = "letta",
            since = t0, now = r4 + minute(10),
        )
        assertEquals(1, waste.size)
        val daily = waste.first().daily
        assertEquals(2, daily.sumOf { it.observed }, "measured tiles: r1+r2, got ${daily.sumOf { it.observed }}")
        assertEquals(2, daily.sumOf { it.estimated }, "gap tiles: r3+r4 only, got ${daily.sumOf { it.estimated }}")
        assertEquals(4, daily.sumOf { it.windows }, "2 measured + 2 reconstructed, no phantoms")
        // (50 + 70 + 90 + 80) / 4 — phantom tiles would skew this
        assertEquals(72.5, waste.first().wastedPctAvg, 0.01)
    }

    @Test
    fun fixedResetProviderWasteMeasuredAtActualResets() {
        // Letta-style daily: resets at fixed midnight times (resetAt jumps daily).
        // Usage pattern: burns to 30% remaining by end of day 1, 60% by day 2.
        val t0 = 1_000_000_000_000L
        val r1 = t0 + 12 * hour           // day-1 reset
        val r2 = r1 + 24 * hour           // day-2 reset
        val snaps = listOf(
            psnap(t0, 100.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r1) },
            psnap(t0 + 6 * hour, 70.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r1) },
            psnap(t0 + 11 * hour, 30.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r1) },
            // after reset 1: fresh window, resets at r2
            psnap(t0 + 12 * hour + minute(5), 100.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r2) },
            psnap(t0 + 20 * hour, 60.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r2) },
            psnap(t0 + 23 * hour, 60.0, windowHours = 24.0, provider = "letta").let { it.copy(resetAt = r2) },
        )
        val waste = FuelIntelligence.providerWaste(snaps, since = t0, now = t0 + 24 * hour)
        assertEquals(1, waste.size)
        val tiles = waste.first().daily.sumOf { it.windows }
        assertEquals(1, tiles) // only reset 1 completed within the timeline
        // Waste at reset 1 = 30% remaining just before expiry
        assertEquals(30.0, waste.first().wastedPctAvg, 0.01)
    }

    @Test
    fun slidingProviderDetectedWhenResetAtMovesEveryPoll() {
        // z.ai-style: resetAt always ~now+5h (moves each poll) → grid tiling
        val t0 = 1_000_000_000_000L
        val snaps = (0..10).map { k ->
            psnap(t0 + k * 30 * 60_000L, 80.0 - k * 5.0, windowHours = 5.0, provider = "zai")
                .let { it.copy(resetAt = t0 + k * 30 * 60_000L + 5 * hour) }
        }
        val waste = FuelIntelligence.providerWaste(snaps, since = t0, now = t0 + 5 * hour)
        assertEquals(1, waste.size) // grid path, not reset-driven
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

    /**
     * Regression: dayStart must be the TRUE local-midnight epoch of the day
     * the window ended in. The old implementation (epochDays × 86_400_000)
     * produced UTC midnight, rendering every waste row one day in the past
     * for negative-UTC-offset timezones.
     */
    @Test
    fun dailyWasteDayStartIsLocalMidnightInNegativeOffsetTimezone() {
        // America/Chicago (CDT = UTC-5). A window ending 2026-09-05 03:00 UTC
        // = 2026-09-04 22:00 local → belongs to local day Sep 4 → dayStart
        // must be Sep 4 00:00 CDT = 2026-09-04T05:00Z = 1_788_498_000_000 ms.
        val windowEndUtcMs = 1_788_577_200_000L // 2026-09-05T03:00:00Z
        val expectedLocalMidnightMs = 1_788_498_000_000L // 2026-09-04T05:00:00Z
        val tiles = listOf(
            FuelIntelligence.WasteTile(windowEnd = windowEndUtcMs, wastedPct = 40.0),
        )
        val daily = FuelIntelligence.dailyWaste(tiles, kotlinx.datetime.TimeZone.of("America/Chicago"))
        assertEquals(1, daily.size)
        assertEquals(expectedLocalMidnightMs, daily[0].dayStart)
        // Sanity: formatting dayStart back in Chicago must yield Sep 4.
        val local = kotlinx.datetime.Instant.fromEpochMilliseconds(daily[0].dayStart)
            .toLocalDateTime(kotlinx.datetime.TimeZone.of("America/Chicago"))
        assertEquals(9, local.monthNumber)
        assertEquals(4, local.dayOfMonth)
        assertEquals(0, local.hour)
    }

    /** Extracts the drop percentage from "Fuel dropped 5.0% ..." descriptions. */
    private fun parseDropPct(description: String): Double =
        Regex("dropped ([0-9.]+)%").find(description)!!.groupValues[1].toDouble()
}
