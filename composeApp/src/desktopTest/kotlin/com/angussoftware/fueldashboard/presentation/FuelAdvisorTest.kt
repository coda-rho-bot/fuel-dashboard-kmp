package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Advisor tests — CORRECTED SEMANTICS (Aug 20 adversarial review).
 *
 * fuel_snapshots.tokensPct is REMAINING % (0 = exhausted, 100 = full):
 *   - exhaustion = remaining falls to/below 5%
 *   - burn erodes remaining downward; projection = current − burn × hours
 *   - at-risk = projected remaining ≤ 0 before reset
 *
 * The original tests encoded the inverted assumption (≥95 = exhausted)
 * and passed because code and test shared the bug.
 */
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
    fun quotaRegimeCountsRecoverySeparatedExhaustions() {
        // remaining 40 → 4 (exhausted) → recovers 95 → 50 → 3 (exhausted again)
        val snaps = listOf(
            snap(now - 3 * day, 40.0),
            snap(now - 3 * day + hour, 4.0),    // event 1 (remaining ≤ 5)
            snap(now - 3 * day + 2 * hour, 4.0), // same plateau
            snap(now - 3 * day + 3 * hour, 95.0), // recovered (window slid)
            snap(now - day, 50.0),
            snap(now - day + hour, 3.0),        // event 2
            snap(now - day + 2 * hour, 30.0),
            snap(now - hour, 60.0),
            snap(now, 70.0),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 3 * hour)
        assertEquals(2, regime.exhaustions)
    }

    @Test
    fun nearlyFullGaugeIsNotExhaustion() {
        // The inversion bug: remaining parked at 96-100 (idle) must NOT count
        val snaps = listOf(
            snap(now - 2 * day, 98.0),
            snap(now - day, 100.0),
            snap(now - hour, 97.0),
            snap(now, 96.0),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 3 * hour)
        assertEquals(0, regime.exhaustions, "idle/high-remaining windows are not exhaustions")
    }

    @Test
    fun pollGapWithinPlateauDoesNotSplitEvent() {
        // exhausted at t0, dashboard down 8h (gap > 6h cluster), still exhausted
        val snaps = listOf(
            snap(now - 20 * hour, 50.0),
            snap(now - 19 * hour, 4.0),
            snap(now - 11 * hour, 4.0), // 8h gap, same plateau continues
            snap(now - 10 * hour, 60.0),
            snap(now, 70.0),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 3 * hour)
        assertEquals(1, regime.exhaustions, "recovery-keyed clustering survives poll gaps")
    }

    @Test
    fun quotaRegimeProjectsRemainingFalling() {
        // remaining 80 → 60 over 2h = burning 10%/hr; 2h to reset:
        // projected remaining = 60 − 10×2 = 40
        val snaps = listOf(
            snap(now - 2 * hour, 80.0, resetAt = now + 2 * hour),
            snap(now - hour, 70.0, resetAt = now + 2 * hour),
            snap(now, 60.0, resetAt = now + 2 * hour),
        )
        val regime = FuelAdvisor.quotaRegime(snaps, now, resetAt = now + 2 * hour)
        assertEquals(10.0, regime.burnPctPerHr!!, 0.5)
        assertEquals(40.0, regime.projectedPctAtReset!!, 1.0)
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
        // Long history, zero exhaustions, healthy projection
        val snaps = buildList {
            for (h in 72 downTo 0) {
                val pct = 30.0 + (h % 5)
                add(snap(now - h * hour, pct, resetAt = now + 4 * hour))
            }
        }
        val advice = FuelAdvisor.advise(snaps, emptyList(), now, resetAt = now + 4 * hour)
        assertTrue(advice is FuelAdvisor.Advice.Surplus || advice is FuelAdvisor.Advice.Healthy,
            "expected no-action advice, got $advice")
    }

    @Test
    fun idleHighRemainingNeverAtRisk() {
        // The inversion signature: gauge pinned high, low burn → no risk
        val snaps = buildList {
            for (h in 24 downTo 0 step 2) {
                add(snap(now - h * hour, 95.0, resetAt = now + 4 * hour))
            }
        }
        val advice = FuelAdvisor.advise(snaps, emptyList(), now, resetAt = now + 4 * hour)
        assertTrue(advice !is FuelAdvisor.Advice.AtRisk, "idle high-remaining must not be at-risk, got $advice")
        assertTrue(advice !is FuelAdvisor.Advice.PersistentPressure, "idle high-remaining must not be pressure, got $advice")
    }

    @Test
    fun atRiskWhenProjectionHitsZeroBeforeReset() {
        // remaining 20, burning 10%/hr, resets in 4h → 20−40 = −20 → at risk
        val snaps = listOf(
            snap(now - 2 * day, 60.0),
            snap(now - 2 * day + hour, 45.0),
            snap(now - 2 * day + 2 * hour, 70.0), // recovery to avoid exhaustion count
            snap(now - 2 * hour, 40.0),
            snap(now - hour, 30.0),
            snap(now, 20.0, resetAt = now + 4 * hour),
        )
        val usage = (0..4).map { d ->
            usage(now - d * day, "conv-cron", "glm-5.2", 10_000)
        }
        val advice = FuelAdvisor.advise(snaps, usage, now, resetAt = now + 4 * hour)
        assertTrue(advice is FuelAdvisor.Advice.AtRisk, "expected AtRisk, got $advice")
        val atRisk = advice as FuelAdvisor.Advice.AtRisk
        assertEquals(1, atRisk.routineConsumers.size)
        assertEquals("conv-cron", atRisk.routineConsumers.first().conversationKey)
    }

    @Test
    fun persistentPressureFromRepeatedRealExhaustions() {
        // 5 distinct exhausted plateaus (remaining → ≤5) across days
        val snaps = buildList {
            add(snap(now - 7 * day, 50.0))
            for (e in 0..4) {
                val base = now - (6 - e) * day
                add(snap(base, 40.0))
                add(snap(base + hour, 4.0))   // exhausted
                add(snap(base + 2 * hour, 30.0)) // recovered
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
