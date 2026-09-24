package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.engine.SwitchCommandResult
import com.angussoftware.fueldashboard.model.ClaudeCodeFleet
import com.angussoftware.fueldashboard.model.ClaudeCodeRoute
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manual "Swap now" path — specifically the parts that can hurt someone.
 *
 * The automatic trigger's guards are covered by SwitchCommandTriggerTest, and
 * deliberately do NOT apply here: a click is the human agreement those guards
 * exist to synthesise. What must survive is the fleet gate, which protects a
 * live agent turn that a click does not make safe to kill.
 *
 * These run on desktop because `switchCommandsSupported` is false elsewhere,
 * which makes the manual entry point a no-op by design.
 */
class SwitchCommandManualTest {

    private val provider = ProviderConfig(
        id = "zai-1",
        kind = ProviderKind.ZAI,
        apiKey = "literal-key",
        activateCommand = "/bin/echo swapped",
        swapAwayBelowPct = 10,
    )

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    /** A view model with the given fleet and a command runner that records calls. */
    private fun viewModel(
        fleet: ClaudeCodeFleet?,
        result: SwitchCommandResult = SwitchCommandResult(exitCode = 0, output = "swapped"),
        ran: MutableList<String> = mutableListOf(),
        decisions: MutableList<List<String?>> = mutableListOf(),
        /** What the routing file says AFTER the command ran. */
        route: ClaudeCodeRoute? = ClaudeCodeRoute(baseUrl = "https://api.z.ai/api/anthropic"),
    ): FuelViewModel {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings(providers = listOf(provider)))
        val vm = FuelViewModel(
            fleetReader = { fleet },
            routeReader = { route },
            switchRunner = { command -> ran.add(command); result },
        )
        vm.onDecisionLogged = { agentId, modelHandle, providerId, tier, complexity, _, _, reason ->
            decisions.add(listOf(agentId, modelHandle, providerId, tier, complexity, reason))
        }
        vm.updateSettings(MultiProviderSettings(providers = listOf(provider)))
        return vm
    }

    /**
     * Captures the status the moment it appears. Asserting against live state
     * later would race the poll loop, which clears a stale result once the
     * provider reports again.
     */
    private fun FuelViewModel.awaitStatus(providerId: String = "zai-1"): SwitchRunStatus =
        runBlocking {
            withTimeout(10_000) {
                var seen: SwitchRunStatus? = null
                while (seen == null) {
                    seen = state.value.switchResults[providerId]
                    if (seen == null) delay(10)
                }
                seen
            }
        }

    @Test
    fun runsOnAQuietFleetAndReportsSuccess() {
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 3, unknown = 0), ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertTrue(status.ok, "quiet fleet must allow the swap: ${status.message}")
        assertEquals(listOf("/bin/echo swapped"), ran)
    }

    @Test
    fun refusesWhileAnySessionIsBusyAndRunsNothing() {
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 2, idle = 20, unknown = 0), ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "a busy fleet must refuse")
        assertTrue(status.message.contains("2"), "refusal should name the busy count: ${status.message}")
        assertEquals(emptyList(), ran, "nothing may run while a turn is in flight")
    }

    @Test
    fun refusesWhenTheFleetCannotBeRead() {
        // "We cannot tell" must never read as "nobody is working".
        val ran = mutableListOf<String>()
        val vm = viewModel(fleet = null, ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "an unreadable fleet must refuse")
        assertEquals(emptyList(), ran)
    }

    @Test
    fun refusesOnAnEmptyRegistry() {
        // total == 0 is deliberately not quiet.
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 0, unknown = 0), ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "an empty registry must refuse")
        assertEquals(emptyList(), ran)
    }

    @Test
    fun refusesWhenASessionStateIsUnknown() {
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 5, unknown = 1), ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "an unknown session must refuse")
        assertEquals(emptyList(), ran)
    }

    @Test
    fun surfacesAFailingCommandRatherThanClaimingSuccess() {
        val vm = viewModel(
            ClaudeCodeFleet(busy = 0, idle = 1, unknown = 0),
            result = SwitchCommandResult(exitCode = 2, output = "boom"),
        )

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "a non-zero exit is not a success")
        assertTrue(status.message.contains("2"), "should carry the exit code: ${status.message}")
    }

    @Test
    fun ignoresTheThresholdEntirely() {
        // The provider is nowhere near swapAwayBelowPct and has never polled, so
        // the automatic trigger would be in Unknown/Healthy and never fire.
        // The manual path must still run.
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 1, unknown = 0), ran = ran)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertTrue(status.ok, "manual swap must not require being below threshold")
        assertEquals(1, ran.size)
    }

    @Test
    fun logsADecisionForBothARunAndARefusal() {
        val taken = mutableListOf<List<String?>>()
        viewModel(ClaudeCodeFleet(busy = 0, idle = 1, unknown = 0), decisions = taken)
            .also { it.runSwitchCommandNow("zai-1") }
            .awaitStatus()

        assertEquals(1, taken.size, "a run must be logged")
        assertEquals("switch-command", taken[0][0])
        assertEquals("zai-1", taken[0][2])
        assertEquals("ok", taken[0][4])

        val refused = mutableListOf<List<String?>>()
        viewModel(ClaudeCodeFleet(busy = 1, idle = 0, unknown = 0), decisions = refused)
            .also { it.runSwitchCommandNow("zai-1") }
            .awaitStatus()

        assertEquals(1, refused.size, "a refusal must be logged too")
        assertEquals("refused", refused[0][4])
    }

    @Test
    fun doesNothingForAProviderWithNoCommandConfigured() {
        val ran = mutableListOf<String>()
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
        val bare = provider.copy(activateCommand = "", swapAwayBelowPct = 0)
        val vm = FuelViewModel(
            fleetReader = { ClaudeCodeFleet(busy = 0, idle = 1, unknown = 0) },
            switchRunner = { command -> ran.add(command); SwitchCommandResult(0, "") },
        )
        vm.updateSettings(MultiProviderSettings(providers = listOf(bare)))

        vm.runSwitchCommandNow("zai-1")

        runBlocking { delay(300) }
        assertEquals(emptyList(), ran)
        assertEquals(null, vm.state.value.switchResults["zai-1"])
    }

    // --- overriding the fleet gate ------------------------------------------

    @Test
    fun aRefusalIsMarkedOverridableSoTheWayPastItCanBeOffered() {
        val vm = viewModel(ClaudeCodeFleet(busy = 1, idle = 22))

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok)
        assertTrue(status.overridable, "a fleet refusal is the one thing an operator may override")
    }

    @Test
    fun aSuccessIsNotOverridable() {
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 23))

        vm.runSwitchCommandNow("zai-1")

        assertFalse(vm.awaitStatus().overridable, "nothing to override when it worked")
    }

    @Test
    fun forcingRunsTheCommandDespiteBusySessions() {
        val ran = mutableListOf<String>()
        val vm = viewModel(ClaudeCodeFleet(busy = 3, idle = 20), ran = ran)

        vm.runSwitchCommandNow("zai-1", force = true)
        val status = vm.awaitStatus()

        assertTrue(status.ok, status.message)
        assertEquals(listOf("/bin/echo swapped"), ran)
    }

    @Test
    fun forcingWorksEvenWhenTheRegistryCannotBeRead() {
        // The gate blocks an unreadable fleet precisely because it cannot tell,
        // and that is exactly the state an operator may know the truth about.
        val ran = mutableListOf<String>()
        val vm = viewModel(fleet = null, ran = ran)

        vm.runSwitchCommandNow("zai-1", force = true)

        assertTrue(vm.awaitStatus().ok)
        assertEquals(listOf("/bin/echo swapped"), ran)
    }

    @Test
    fun anOverriddenGateIsRecordedInTheDecisionLog() {
        // The one case where a killed turn was somebody's choice rather than
        // something the app prevented, so the log has to say so.
        val decisions = mutableListOf<List<String?>>()
        val vm = viewModel(ClaudeCodeFleet(busy = 2, idle = 20), decisions = decisions)

        vm.runSwitchCommandNow("zai-1", force = true)
        vm.awaitStatus()

        val reason = decisions.single().last().orEmpty()
        assertTrue("FLEET GATE OVERRIDDEN" in reason, reason)
        assertTrue("busy=2" in reason, reason)
    }

    @Test
    fun forcingClaimsNoOverrideWhenTheFleetWasAlreadyQuiet() {
        // force only bypasses. When the gate would have allowed it anyway,
        // nothing was overridden and the log must not say otherwise.
        val decisions = mutableListOf<List<String?>>()
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 23), decisions = decisions)

        vm.runSwitchCommandNow("zai-1", force = true)
        vm.awaitStatus()

        val reason = decisions.single().last().orEmpty()
        assertFalse("FLEET GATE OVERRIDDEN" in reason, reason)
    }

    // --- did the swap actually take effect? ---------------------------------

    @Test
    fun aSwapConfirmedByTheRoutingFileReportsSuccess() {
        // The provider under test is z.ai, and the route now points at z.ai's
        // endpoint, so the swap demonstrably happened.
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 3))

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertTrue(status.ok, status.message)
        assertTrue("Swapped to" in status.message, status.message)
    }

    @Test
    fun aCommandThatExitsZeroButMovesNothingIsNotSuccess() {
        // The case that prompted this: a placeholder command exited 0 and the
        // card announced a swap while the badge two lines above still showed
        // the old provider. An exit code says the command ran, not that the
        // provider moved.
        val vm = viewModel(
            ClaudeCodeFleet(busy = 0, idle = 3),
            // Routing unchanged: still stock Anthropic.
            route = ClaudeCodeRoute(baseUrl = null),
        )

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok, "nothing moved, so this is not a success")
        assertTrue("nothing moved" in status.message, status.message)
        assertTrue("Anthropic" in status.message, "it should name where we actually are: ${status.message}")
    }

    @Test
    fun anUnreadableRouteIsReportedAsUnconfirmedNotAsFailure() {
        // "It did not take effect" and "I could not tell" are different facts.
        // Conflating them would cry wolf whenever the routing file is
        // unreadable, which is not the same as a broken command.
        val vm = viewModel(ClaudeCodeFleet(busy = 0, idle = 3), route = null)

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertTrue(status.ok, "the command did succeed; only confirmation is missing")
        assertTrue("could not be read" in status.message, status.message)
    }

    @Test
    fun aFailedCommandIsStillReportedAsAFailure() {
        val vm = viewModel(
            ClaudeCodeFleet(busy = 0, idle = 3),
            result = SwitchCommandResult(exitCode = 3, output = "boom"),
        )

        vm.runSwitchCommandNow("zai-1")
        val status = vm.awaitStatus()

        assertFalse(status.ok)
        assertTrue("Swap failed" in status.message, status.message)
    }
}
