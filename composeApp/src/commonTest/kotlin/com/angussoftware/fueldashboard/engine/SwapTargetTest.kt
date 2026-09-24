package com.angussoftware.fueldashboard.engine

import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where work goes when a provider runs dry.
 *
 * Every case here is a way for the answer to be "stay put", because the cost
 * of a wrong yes is a fleet restarted onto a provider that cannot serve it,
 * while the cost of a wrong no is staying somewhere low a little longer.
 */
class SwapTargetTest {

    private fun provider(id: String, command: String = "activate $id") =
        ProviderConfig(id = id, kind = ProviderKind.ZAI, apiKey = "k", activateCommand = command)

    private val primary = provider("primary")
    private val backup = provider("backup")
    private val spare = provider("spare")

    @Test
    fun picksTheHealthiestCandidate() {
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 5,
            providers = listOf(primary, backup, spare),
            remainingById = mapOf("primary" to 5, "backup" to 40, "spare" to 80),
        )
        assertEquals("spare", target?.id)
    }

    @Test
    fun neverPicksTheProviderBeingLeft() {
        // Even when it somehow looks like the best option.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 5,
            providers = listOf(primary),
            remainingById = mapOf("primary" to 99),
        )
        assertNull(target)
    }

    @Test
    fun aProviderWithNoActivateCommandCannotBeArrivedAt() {
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 5,
            providers = listOf(primary, provider("backup", command = "")),
            remainingById = mapOf("primary" to 5, "backup" to 90),
        )
        assertNull(target, "90% is no use if there is no way to switch to it")
    }

    @Test
    fun anUnknownLevelIsNeverACandidate() {
        // The invariant that matters most here. An absent reading means the
        // gauge could not be read — treating it as usable would move the fleet
        // onto a provider nobody can see the level of.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 5,
            providers = listOf(primary, backup),
            remainingById = mapOf("primary" to 5), // backup unknown
        )
        assertNull(target)
    }

    @Test
    fun willNotMoveSomewhereWorse() {
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 50,
            providers = listOf(primary, backup),
            remainingById = mapOf("primary" to 50, "backup" to 20),
        )
        assertNull(target, "moving from 50% to 20% achieves nothing")
    }

    @Test
    fun willNotMoveSomewhereEqual() {
        // Strictly better, not merely as good — otherwise two providers sitting
        // at the same level can ping-pong on every excursion.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 30,
            providers = listOf(primary, backup),
            remainingById = mapOf("primary" to 30, "backup" to 30),
        )
        assertNull(target)
    }

    @Test
    fun anUnknownLevelWhereWeAreMeansStayPut() {
        // With no reading for the current provider there is no "better" to
        // compare against, so there is nothing to justify a move.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = null,
            providers = listOf(primary, backup),
            remainingById = mapOf("backup" to 90),
        )
        assertNull(target)
    }

    @Test
    fun emptyAndSingleProviderSetupsHoldWithoutThrowing() {
        assertNull(SwapTarget.choose("primary", 5, emptyList(), emptyMap()))
        assertNull(SwapTarget.choose("primary", 5, listOf(primary), mapOf("primary" to 5)))
    }

    @Test
    fun aTieResolvesToUserOrderSoTheChoiceIsStable() {
        // maxByOrNull keeps the first maximum, so the answer does not flip
        // between polls while two providers sit level.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 5,
            providers = listOf(primary, backup, spare),
            remainingById = mapOf("primary" to 5, "backup" to 70, "spare" to 70),
        )
        assertEquals("backup", target?.id)
    }

    @Test
    fun aBarelyBetterCandidateStillCounts() {
        // Deliberately no minimum margin: the guards that stop thrashing are
        // the agreement streak and cooldown in SwitchCommandTrigger, not an
        // arbitrary gap invented here.
        val target = SwapTarget.choose(
            fromId = "primary",
            fromRemaining = 9,
            providers = listOf(primary, backup),
            remainingById = mapOf("primary" to 9, "backup" to 10),
        )
        assertEquals("backup", target?.id)
    }
}
