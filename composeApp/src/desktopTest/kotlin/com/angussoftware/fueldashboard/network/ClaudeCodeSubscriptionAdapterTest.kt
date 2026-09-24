package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ClaudeCodeUsageLimit
import com.angussoftware.fueldashboard.model.ClaudeCodeUsageResponse
import com.angussoftware.fueldashboard.model.ClaudeCodeUsageWindow
import com.angussoftware.fueldashboard.model.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Claude Code plan gauge, mapped from the `/api/oauth/usage` payload.
 *
 * The load-bearing property under test is that an absent utilization stays
 * absent. A fabricated percentage does not just mislead the tile — it feeds
 * burn-rate history, the waste tiles, the Advisor's regime classification and
 * alert suppression, so "unknown" reported as 0% or 100% corrupts all of them.
 */
class ClaudeCodeSubscriptionAdapterTest {

    private val adapter = ClaudeCodeSubscriptionAdapter(
        providerId = "cc-1",
        tokenProvider = { "unused — mapping is tested directly" },
    )

    /** The real payload, captured live 2026-09-23 at 5h 17% / weekly 77%. */
    private fun liveResponse() = ClaudeCodeUsageResponse(
        fiveHour = ClaudeCodeUsageWindow(
            utilization = 17.0,
            resetsAt = "2026-09-24T03:39:59.735686+00:00",
        ),
        sevenDay = ClaudeCodeUsageWindow(
            utilization = 77.0,
            resetsAt = "2026-09-25T22:59:59.735707+00:00",
        ),
        limits = listOf(
            ClaudeCodeUsageLimit(kind = "session", severity = "normal"),
            ClaudeCodeUsageLimit(kind = "weekly_all", severity = "warning"),
        ),
    )

    @Test
    fun mapsBothWindowsFromTheLivePayload() {
        val report = adapter.mapToProviderReport(liveResponse())

        assertEquals(ProviderType.WINDOW_CREDIT, report.type)
        assertTrue(report.available)
        assertEquals(2, report.windows.size)

        val fiveHour = report.windows[0]
        assertEquals("5-hour", fiveHour.name)
        assertEquals(83, fiveHour.remainingPct, "utilization 17% -> 83% remaining")
        assertEquals(5.0, fiveHour.windowHours)
        assertEquals(1_790_221_199_735L, fiveHour.resetsAt)
        assertFalse(fiveHour.resetEstimated)

        val weekly = report.windows[1]
        assertEquals("Weekly", weekly.name)
        assertEquals(23, weekly.remainingPct, "utilization 77% -> 23% remaining")
        assertEquals(168.0, weekly.windowHours)
    }

    @Test
    fun headlineComesFromTheFiveHourWindowOnly() {
        // The headline's %, reset and window length must all come from ONE
        // window — mixing them pairs one window's percentage with another's
        // countdown (PR #84).
        val report = adapter.mapToProviderReport(liveResponse())

        assertEquals(83, report.remainingPct)
        assertEquals(5.0, report.windowHours)
        assertEquals(report.windows[0].resetsAt, report.resetsAt)
    }

    @Test
    fun windowLengthsAreConstantsNotDerivedFromTheResetInstant() {
        // Deriving length from resets_at makes elapsed == total, pinning the
        // hourglass at 100% forever (review 1975). A window resetting in one
        // second is still a 5-hour window.
        val imminent = ClaudeCodeUsageResponse(
            fiveHour = ClaudeCodeUsageWindow(utilization = 50.0, resetsAt = "2026-09-23T00:00:01+00:00"),
            sevenDay = ClaudeCodeUsageWindow(utilization = 50.0, resetsAt = "2026-09-23T00:00:02+00:00"),
        )
        val report = adapter.mapToProviderReport(imminent)

        assertEquals(5.0, report.windows[0].windowHours)
        assertEquals(168.0, report.windows[1].windowHours)
    }

    @Test
    fun absentUtilizationIsUnknownNotZeroAndNotFull() {
        val report = adapter.mapToProviderReport(
            ClaudeCodeUsageResponse(
                fiveHour = ClaudeCodeUsageWindow(utilization = null, resetsAt = "2026-09-24T03:39:59Z"),
                sevenDay = ClaudeCodeUsageWindow(utilization = 77.0, resetsAt = "2026-09-25T22:59:59Z"),
            ),
        )

        assertEquals(1, report.windows.size, "the unknown window must be omitted, not invented")
        assertEquals("Weekly", report.windows.single().name)
        // With no 5-hour reading the headline falls through to the weekly one
        // rather than reporting a fabricated 0% or 100%.
        assertEquals(23, report.remainingPct)
        assertEquals(168.0, report.windowHours)
    }

    @Test
    fun entirelyEmptyPayloadReportsUnavailable() {
        val report = adapter.mapToProviderReport(ClaudeCodeUsageResponse())

        assertFalse(report.available, "no readings at all must not present as a full tank")
        assertNull(report.remainingPct)
        assertNull(report.resetsAt)
        assertEquals(0.0, report.windowHours)
        assertTrue(report.windows.isEmpty())
    }

    @Test
    fun unparseableResetInstantKeepsThePercentageAndFlagsTheEstimate() {
        val report = adapter.mapToProviderReport(
            ClaudeCodeUsageResponse(
                fiveHour = ClaudeCodeUsageWindow(utilization = 40.0, resetsAt = "not a timestamp"),
            ),
        )

        val window = report.windows.single()
        assertEquals(60, window.remainingPct, "a bad timestamp must not discard a good percentage")
        assertNull(window.resetsAt)
        assertTrue(window.resetEstimated)
    }

    @Test
    fun utilizationBeyondTheWindowClampsInsteadOfGoingNegative() {
        val report = adapter.mapToProviderReport(
            ClaudeCodeUsageResponse(
                fiveHour = ClaudeCodeUsageWindow(utilization = 118.0),
                sevenDay = ClaudeCodeUsageWindow(utilization = -4.0),
            ),
        )

        assertEquals(0, report.windows[0].remainingPct)
        assertEquals(100, report.windows[1].remainingPct)
    }

    @Test
    fun severityPrefersTheWeeklyLimitAndSurvivesAnAbsentOne() {
        assertEquals("warning", adapter.mapToProviderReport(liveResponse()).detail)

        val sessionOnly = adapter.mapToProviderReport(
            ClaudeCodeUsageResponse(
                fiveHour = ClaudeCodeUsageWindow(utilization = 10.0),
                limits = listOf(ClaudeCodeUsageLimit(kind = "session", severity = "normal")),
            ),
        )
        assertEquals("normal", sessionOnly.detail)

        assertNull(
            adapter.mapToProviderReport(
                ClaudeCodeUsageResponse(fiveHour = ClaudeCodeUsageWindow(utilization = 10.0)),
            ).detail,
        )
    }

    @Test
    fun rawDisplayReportsUtilizationAndOmitsWhatIsMissing() {
        assertEquals("used 5h:17% weekly:77%", adapter.mapToProviderReport(liveResponse()).rawDisplay)
        assertEquals(
            "used weekly:77%",
            adapter.mapToProviderReport(
                ClaudeCodeUsageResponse(sevenDay = ClaudeCodeUsageWindow(utilization = 77.0)),
            ).rawDisplay,
        )
        assertEquals("", adapter.mapToProviderReport(ClaudeCodeUsageResponse()).rawDisplay)
    }
}
