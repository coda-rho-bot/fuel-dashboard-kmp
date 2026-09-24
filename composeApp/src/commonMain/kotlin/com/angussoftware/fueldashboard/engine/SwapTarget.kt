package com.angussoftware.fueldashboard.engine

import com.angussoftware.fueldashboard.model.ProviderConfig

/**
 * Chooses which provider to move work to when another has run dry.
 *
 * Kept pure and separate from the view model for the same reason
 * [SwitchCommandTrigger] is: this decides that a command which restarts a
 * fleet should run, so it needs to be readable and exhaustively testable on
 * its own.
 *
 * Picking the healthiest candidate is what makes "do not switch onto an empty
 * tank" a property of the choice rather than a gate bolted on after it. The
 * rules below can each rule out every candidate, and no candidate means stay
 * put — nowhere better to be is a reason not to move, not a reason to move
 * anyway.
 */
object SwapTarget {

    /**
     * The provider to activate, or null to stay where we are.
     *
     * @param fromId the provider that has run dry.
     * @param fromRemaining its remaining percent, or null if unknown.
     * @param providers every configured provider, in user order.
     * @param remainingById remaining percent per provider id, containing only
     *   the ones actually KNOWN. An absent entry means unreadable, which is
     *   never treated as a full tank.
     */
    fun choose(
        fromId: String,
        fromRemaining: Int?,
        providers: List<ProviderConfig>,
        remainingById: Map<String, Int>,
    ): ProviderConfig? {
        // Without a reading for where we are, "somewhere better" has no
        // meaning, so there is nothing to compare against and we hold.
        val floor = fromRemaining ?: return null

        return providers
            .asSequence()
            // A provider with no activate command cannot be arrived at.
            .filter { it.id != fromId && it.activateCommand.isNotBlank() }
            // Unknown is not good news. This is the same invariant the rest of
            // the app applies to a missing gauge, and it matters most here:
            // treating unreadable as usable would move a fleet onto a provider
            // nobody can see the level of.
            .mapNotNull { candidate -> remainingById[candidate.id]?.let { candidate to it } }
            // Moving somewhere worse achieves nothing and invites thrashing
            // between two nearly-empty providers.
            .filter { (_, remaining) -> remaining > floor }
            // Ties resolve to the earliest in user order, which is stable
            // across polls — maxByOrNull keeps the first maximum it sees.
            .maxByOrNull { (_, remaining) -> remaining }
            ?.first
    }
}
