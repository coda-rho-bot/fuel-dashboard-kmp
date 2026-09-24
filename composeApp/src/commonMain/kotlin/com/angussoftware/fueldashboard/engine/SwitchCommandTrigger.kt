package com.angussoftware.fueldashboard.engine

/**
 * Decides whether a provider's configured switch command should run.
 *
 * This is the whole policy, as a pure function, because the thing it guards is
 * destructive: the command exists to move real work off a provider, and for
 * the case that motivated it that means restarting every live agent session.
 * A gauge that flickers across the threshold must not restart a fleet
 * repeatedly, and a provider that simply stopped reporting must not look like
 * a provider that ran dry.
 *
 * Four properties, in order of importance:
 *
 * 1. **An unknown reading never fires.** `remainingPct == null` means the poll
 *    failed or the provider omitted the field — not that quota is gone. It
 *    also does not reset the streak, so a single failed poll mid-descent
 *    neither triggers nor un-triggers anything.
 * 2. **Agreement before action.** [requiredStreak] consecutive polls must
 *    agree that the provider is below the threshold.
 * 3. **Fire once per excursion.** After firing, the trigger disarms and only
 *    re-arms once the provider recovers above the threshold. Without this a
 *    provider sitting at 2% would fire on every poll forever.
 * 4. **Cooldown.** Even across a recovery, [cooldownMs] must elapse between
 *    runs, so a gauge oscillating around the threshold cannot thrash.
 */
object SwitchCommandTrigger {

    /** Consecutive sub-threshold polls required before firing. */
    const val DEFAULT_REQUIRED_STREAK = 2

    /** Minimum gap between two runs of the same provider's command. */
    const val DEFAULT_COOLDOWN_MS = 30L * 60 * 1000

    /**
     * Per-provider trigger state. Carried by the caller across polls; it is
     * intentionally not persisted, so a restart begins disarmed-by-recovery
     * rather than resuming a half-counted streak.
     */
    data class State(
        val belowStreak: Int = 0,
        val armed: Boolean = true,
        val lastFiredAt: Long? = null,
    )

    sealed interface Outcome {
        /** No usable threshold, so this provider has no trigger armed. */
        data object Disabled : Outcome

        /** The provider did not report a percentage. Never an action. */
        data object Unknown : Outcome

        /** Above the threshold; the trigger is (re-)armed. */
        data object Healthy : Outcome

        /** Below the threshold but still gathering agreement. */
        data class Watching(val streak: Int, val required: Int) : Outcome

        /** Below threshold, agreed, but already fired for this excursion. */
        data object AlreadyFired : Outcome

        /** Below threshold and agreed, but too soon after the last run. */
        data class CoolingDown(val msRemaining: Long) : Outcome

        /** Run the command. [reason] is recorded in the decision log. */
        data class Fire(val reason: String) : Outcome
    }

    /**
     * Evaluates one poll for one provider.
     *
     * Returns the decision plus the state to carry into the next poll. The
     * caller must persist the returned state even when nothing fires —
     * dropping it resets the streak and agreement can never be reached.
     */
    fun evaluate(
        thresholdPct: Int,
        remainingPct: Int?,
        state: State,
        now: Long,
        requiredStreak: Int = DEFAULT_REQUIRED_STREAK,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Pair<Outcome, State> {
        // No longer keyed on a command: which command runs is the caller's
        // decision, because a depleting provider is moved AWAY from and the
        // command that runs belongs to wherever it is moved TO.
        if (thresholdPct <= 0) return Outcome.Disabled to state

        // Unknown is not low. Hold the streak where it is so one failed poll
        // neither fires nor discards the agreement built up so far.
        if (remainingPct == null) return Outcome.Unknown to state

        if (remainingPct >= thresholdPct) {
            // Recovery re-arms, which is the only way back from a fired state.
            return Outcome.Healthy to state.copy(belowStreak = 0, armed = true)
        }

        val streak = state.belowStreak + 1
        val advanced = state.copy(belowStreak = streak)

        if (streak < requiredStreak) {
            return Outcome.Watching(streak, requiredStreak) to advanced
        }
        if (!advanced.armed) {
            return Outcome.AlreadyFired to advanced
        }
        val since = state.lastFiredAt?.let { now - it }
        if (since != null && since < cooldownMs) {
            return Outcome.CoolingDown(cooldownMs - since) to advanced
        }

        val reason = "$remainingPct% remaining, below the $thresholdPct% threshold " +
            "on $streak consecutive polls"
        return Outcome.Fire(reason) to advanced.copy(armed = false, lastFiredAt = now)
    }
}
