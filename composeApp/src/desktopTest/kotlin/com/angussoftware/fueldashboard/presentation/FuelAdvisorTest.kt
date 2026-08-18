package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuelAdvisorTest {

    private fun snap(ts: Long, pct: Double, resetAt: Long? = null) =
        FuelSnapshotRecord(0L, ts, pct, null, 0, null, resetAt)

    private fun usage(ts: Long, conv: String, model: String, tokens: Long) = UsageRecord(
        0L, ts, "Coda", model, conv, tokens, tokens / 8, 1L,
    )

    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val now = 100L * day

    // ── Regime ──────────────────────────────────────────────────────────

    @Test
    fun quotaRegimeCountsClusteredExhaustions() {
        // Two separate exhaustion events (spaced > 6h), plus recovery between
        val snaps = listOf(
            snap(now - 3 * day, 40.0),
            snap(now - 3 * day + hour, 95.0),   // event 1
            snap(now - 3 * day + 2 * hour, 98.0), // same event (clustered)
            snap(now - 3 * day + 3 * hour, 20.0), // reset slid
            snap(now - day, 50.0),
            snap(now - day + hour, 96.0),       // event 2
            snap(now - day + 2 * hour, 30.0),
            snap(now - hour, 60.0),
            snap(now, 70.0),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 3 * hour)
        assertEquals(2, regime.exhaustions)
    }

    @Test
    fun quotaRegimeProjectsResetLevel() {
        // Burning 10%/hr with 2h to reset from 60% → 60 + 20 = 80 at reset
        val snaps = listOf(
            snap(now - 2 * hour, 80.0, resetAt = now + 2 * hour),
            snap(now - hour, 70.0, resetAt = now + 2 * hour),
            snap(now, 60.0, resetAt = now + 2 * hour),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 2 * hour)
        assertEquals(10.0, regime.burnPctPerHr!!, 0.5)
        assertEquals(80.0, regime.projectedPctAtReset!!, 1.0)
    }

    // ── Routine classification ──────────────────────────────────────────

    @Test
    fun routineClassificationRequiresThreeDays() {
        val routine = (0..4).map { d -> usage(now - d * day, "conv-cron", "glm-5.2", 10_000) }
        val once = listOf(usage(now, "conv-interactive", "glm-5.2", 500_000))
        val consumers = FuelAdvisor.routineConsumers(routine + once, now)
        assertEquals(1, consumers.size)
        assertEquals("conv-cron", consumers.first().conversationKey)
        assertEquals(5, consumers.first().activeDays)
        // glm-5.2 (6.9/24) → glm-4.7 (4.6/16): input-heavy mix saves ~33%
        assertTrue(consumers.first().savingsFraction > 0.2, "expected savings, got ${consumers.first().savingsFraction}")
    }

    @Test
    fun interactiveSessionsNeverClassifiedRoutine() {
        val big = (0..1).map { d -> usage(now - d * day, "conv-big", "glm-5.2", 1_000_000) }
        assertTrue(FuelAdvisor.routineConsumers(big, now).isEmpty())
    }

    // ── Advice states ───────────────────────────────────────────────────

    @Test
    fun surplusRegimeSaysNoAction() {
        // Long history, zero exhaustions, projected fine
        val snaps = buildList {
            for (h in 72 downTo 0) {
                val pct = if (h % 10 == 0) 30.0 else 20.0 + (h % 5)
                add(snap(now - h * hour, pct, resetAt = now + 4 * hour))
            }
        }
        val advice = FuelAdvisor.advise(snaps, emptyList(), now, resetAt = now + 4 * hour)
        assertTrue(advice is FuelAdvisor.Advice.Surplus || advice is FuelAdvisor.Advice.Healthy,
            "expected no-action advice, got $advice")
    }

    @Test
    fun atRiskWindowFlagsAndTargetsRoutineOnly() {
        // Current: 90%, burning 5%/hr, resets in 2h → 90+10 = 100 → exhausts.
        // Span 2 days so windowsAnalyzed >= 2 (not InsufficientData).
        val snaps = listOf(
            snap(now - 2 * day, 40.0),
            snap(now - 2 * day + hour, 96.0),   // prior exhaustion
            snap(now - 2 * day + 2 * hour, 20.0),
            snap(now - 2 * hour, 100.0),
            snap(now - hour, 95.0),
            snap(now, 90.0, resetAt = now + 2 * hour),
        )
        val usage = (0..4).map { d ->
            usage(now - d * day, "conv-cron", "glm-5.2", 10_000)
        }
        val advice = FuelAdvisor.advise(snaps, usage, now, resetAt = now + 2 * hour)
        assertTrue(advice is FuelAdvisor.Advice.AtRisk, "expected AtRisk, got $advice")
        val atRisk = advice as FuelAdvisor.Advice.AtRisk
        assertEquals(1, atRisk.routineConsumers.size)
        assertEquals("conv-cron", atRisk.routineConsumers.first().conversationKey)
    }

    @Test
    fun persistentPressureFromRepeatedExhaustions() {
        // 5 distinct exhaustion events across days
        val snaps = buildList {
            add(snap(now - 7 * day, 50.0))
            for (e in 0..4) {
                val base = now - (6 - e) * day
                add(snap(base, 40.0))
                add(snap(base + hour, 96.0))   // exhaustion
                add(snap(base + 2 * hour, 30.0))
            }
            add(snap(now, 50.0, resetAt = now + 4 * hour))
        }
        val advice = FuelAdvisor.advise(snaps, emptyList(), now, resetAt = now + 4 * hour)
        assertTrue(advice is FuelAdvisor.Advice.PersistentPressure, "expected PersistentPressure, got $advice")
        assertEquals(5, (advice as FuelAdvisor.Advice.PersistentPressure).regime.exhaustions)
    }

    @Test
    fun insufficientDataWhenNoHistory() {
        val advice = FuelAdvisor.advise(emptyList(), emptyList(), now, null)
        assertEquals(FuelAdvisor.Advice.InsufficientData, advice)
    }
}
