package com.angussoftware.fueldashboard.engine

import com.angussoftware.fueldashboard.engine.SwitchCommandTrigger.Outcome
import com.angussoftware.fueldashboard.engine.SwitchCommandTrigger.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trigger is the only thing standing between a flickering gauge and a
 * command that restarts a live agent fleet, so each guard is pinned
 * separately.
 */
class SwitchCommandTriggerTest {

    private val threshold = 15

    private fun eval(
        pct: Int?,
        state: State = State(),
        now: Long = 1_000_000L,
        thresholdPct: Int = threshold,
    ) = SwitchCommandTrigger.evaluate(thresholdPct, pct, state, now)

    @Test
    fun zeroThresholdIsDisabledSoUpgradingChangesNothing() {
        val (outcome, state) = eval(pct = 1, thresholdPct = 0)
        assertEquals(Outcome.Disabled, outcome)
        assertEquals(State(), state, "a disabled trigger must not accumulate state")
    }

    @Test
    fun unknownReadingNeverFires() {
        // The load-bearing one: a failed poll or an omitted field is not "the
        // tank is empty". Firing here would restart the fleet because the
        // network hiccupped.
        val (outcome, state) = eval(pct = null)
        assertEquals(Outcome.Unknown, outcome)
        assertEquals(0, state.belowStreak)
    }

    @Test
    fun unknownReadingDoesNotDiscardAgreementAlreadyBuilt() {
        val (_, afterOne) = eval(pct = 5)
        assertIs<Outcome.Watching>(eval(pct = 5).first)
        assertEquals(1, afterOne.belowStreak)

        val (outcome, afterUnknown) = eval(pct = null, state = afterOne)
        assertEquals(Outcome.Unknown, outcome)
        assertEquals(1, afterUnknown.belowStreak, "one bad poll must not reset the streak")

        // The next real sub-threshold reading completes the agreement.
        assertIs<Outcome.Fire>(eval(pct = 5, state = afterUnknown).first)
    }

    @Test
    fun requiresTwoAgreeingPollsBeforeFiring() {
        val (first, s1) = eval(pct = 9)
        assertEquals(Outcome.Watching(1, 2), first)

        val (second, s2) = eval(pct = 9, state = s1)
        assertIs<Outcome.Fire>(second)
        assertTrue(second.reason.contains("9%"))
        assertTrue(second.reason.contains("15%"))
        assertEquals(false, s2.armed, "firing must disarm")
        assertEquals(1_000_000L, s2.lastFiredAt)
    }

    @Test
    fun atThresholdIsHealthyNotBelow() {
        // Boundary: the threshold is "below this", so exactly-at is fine.
        assertEquals(Outcome.Healthy, eval(pct = threshold).first)
        assertIs<Outcome.Watching>(eval(pct = threshold - 1).first)
    }

    @Test
    fun recoveryResetsTheStreakAndRearms() {
        val (_, s1) = eval(pct = 5)
        val (_, fired) = eval(pct = 5, state = s1)
        assertEquals(false, fired.armed)

        val (outcome, recovered) = eval(pct = 80, state = fired)
        assertEquals(Outcome.Healthy, outcome)
        assertEquals(0, recovered.belowStreak)
        assertTrue(recovered.armed, "recovery is the only way back from fired")
        assertEquals(fired.lastFiredAt, recovered.lastFiredAt, "recovery must not clear the cooldown")
    }

    @Test
    fun staysLowWithoutRecoveringFiresOnlyOnce() {
        // A provider pinned at 2% would otherwise re-fire on every poll.
        var state = State()
        var fires = 0
        repeat(20) {
            val (outcome, next) = eval(pct = 2, state = state, now = 1_000_000L + it * 30_000L)
            if (outcome is Outcome.Fire) fires++
            state = next
        }
        assertEquals(1, fires, "one excursion must produce exactly one run")
    }

    @Test
    fun cooldownBlocksARapidSecondExcursion() {
        val (_, s1) = eval(pct = 5)
        val (_, fired) = eval(pct = 5, state = s1, now = 1_000_000L)

        // Recover (re-arms) then dip again well inside the cooldown.
        val (_, recovered) = eval(pct = 90, state = fired, now = 1_060_000L)
        val (_, dipped) = eval(pct = 5, state = recovered, now = 1_120_000L)
        val (outcome, _) = eval(pct = 5, state = dipped, now = 1_180_000L)

        val cooling = assertIs<Outcome.CoolingDown>(outcome)
        assertTrue(cooling.msRemaining > 0)
    }

    @Test
    fun cooldownExpiryAllowsTheNextExcursion() {
        val (_, s1) = eval(pct = 5)
        val (_, fired) = eval(pct = 5, state = s1, now = 0L)
        val (_, recovered) = eval(pct = 90, state = fired, now = 1_000L)

        val past = SwitchCommandTrigger.DEFAULT_COOLDOWN_MS + 1
        val (_, dipped) = eval(pct = 5, state = recovered, now = past)
        assertIs<Outcome.Fire>(eval(pct = 5, state = dipped, now = past + 1).first)
    }

    @Test
    fun freshStateHasNoFireTimestamp() {
        assertNull(State().lastFiredAt)
        assertTrue(State().armed)
    }
}
