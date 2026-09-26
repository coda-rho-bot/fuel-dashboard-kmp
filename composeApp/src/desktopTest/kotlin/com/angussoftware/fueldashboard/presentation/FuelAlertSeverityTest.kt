package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which provider running dry is worth shouting about.
 *
 * The panel only works if a CRITICAL means "your work stops now". A provider
 * you are not routed to hitting 0% does not stop anything, and with several
 * accounts configured those would fill the panel with sirens that never
 * mattered — at which point the real one is indistinguishable from the noise.
 */
class FuelAlertSeverityTest {

    private val vm = FuelViewModel()

    @AfterTest
    fun close() = vm.close()

    private fun report(id: String, name: String, pct: Int?, available: Boolean = true) =
        ProviderReport(
            providerId = id,
            displayName = name,
            type = ProviderType.WINDOW_CREDIT,
            remainingPct = pct,
            available = available,
        )

    private fun alerts(vararg reports: Pair<String, ProviderReport>, serving: String?) =
        vm.generateFuelAlerts(reports.toMap(), serving)

    @Test
    fun theProviderInUseStillRaisesCritical() {
        val out = alerts("cc" to report("cc", "Claude Code plan", 3), serving = "cc")
        assertEquals(listOf("CRITICAL: Claude Code plan at 3%"), out)
    }

    @Test
    fun theProviderInUseStillRaisesWarning() {
        val out = alerts("cc" to report("cc", "Claude Code plan", 20), serving = "cc")
        assertEquals(listOf("WARNING: Claude Code plan at 20%"), out)
    }

    @Test
    fun aProviderNotInUseDoesNotRaiseCritical() {
        // The reported complaint: on Claude, with z.ai empty and unused, a
        // pink CRITICAL banner for z.ai is noise.
        val out = alerts(
            "cc" to report("cc", "Claude Code plan", 80),
            "zai" to report("zai", "z.ai Coding Plan", 0),
            serving = "cc",
        )
        assertEquals(1, out.size, out.toString())
        assertTrue(out.single().startsWith("z.ai Coding Plan is empty"), out.single())
        assertTrue(
            out.none { it.startsWith("CRITICAL") || it.startsWith("WARNING") },
            "an unused provider must not reach a shouting severity: $out",
        )
    }

    @Test
    fun aProviderNotInUseAndMerelyLowSaysNothingAtAll() {
        // Low-but-not-empty on a provider you are not using is not news.
        val out = alerts(
            "cc" to report("cc", "Claude Code plan", 80),
            "zai" to report("zai", "z.ai Coding Plan", 20),
            serving = "cc",
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun aNearlyEmptyUnusedProviderIsNearlyEmptyNotEmpty() {
        // 5% is low, not gone — the wording must not claim "empty" when the
        // gauge still reads.
        val out = alerts("zai" to report("zai", "z.ai Coding Plan", 5), serving = "cc")
        assertEquals(listOf("z.ai Coding Plan is nearly empty (5%) — not in use"), out)
    }

    @Test
    fun anEmptyUnusedProviderIsStillMentionedOnce() {
        // Not silence: it is the fallback you would swap to, and finding it
        // gone at that moment is worse than a quiet note now.
        val out = alerts("zai" to report("zai", "z.ai Coding Plan", 0), serving = "cc")
        assertEquals(listOf("z.ai Coding Plan is empty (0%) — not in use"), out)
    }

    @Test
    fun withNoRoutingSignalEverySeverityIsUnchanged() {
        // Anyone not routing Claude Code through this app has no "in use"
        // provider, so behaviour must be exactly what it was before.
        val out = alerts(
            "a" to report("a", "Alpha", 3),
            "b" to report("b", "Beta", 20),
            serving = null,
        )
        assertTrue("CRITICAL: Alpha at 3%" in out, out.toString())
        assertTrue("WARNING: Beta at 20%" in out, out.toString())
    }

    @Test
    fun unknownAndUnavailableReadingsRaiseNothing() {
        // An unreadable gauge is not an empty one — the invariant the rest of
        // the app applies.
        val out = alerts(
            "a" to report("a", "Alpha", null),
            "b" to report("b", "Beta", 0, available = false),
            serving = null,
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun aHealthyProviderInUseRaisesNothing() {
        assertEquals(emptyList(), alerts("cc" to report("cc", "Claude", 90), serving = "cc"))
    }
}
