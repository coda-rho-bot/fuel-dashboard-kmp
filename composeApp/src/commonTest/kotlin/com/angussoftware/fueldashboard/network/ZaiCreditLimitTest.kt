package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ZaiQuotaData
import com.angussoftware.fueldashboard.model.ZaiQuotaLimit
import com.angussoftware.fueldashboard.model.ZaiQuotaResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coding Plan (`level: "pro"`) accounts report `CREDIT_LIMIT` rows instead of
 * the `TOKENS_LIMIT` / `SESSION_LIMIT` pair the adapter originally handled, so
 * such an account showed UNAVAILABLE while the response carried real quota.
 *
 * The fixture is the live payload captured from a pro account on 2026-09-24.
 */
class ZaiCreditLimitTest {

    private val adapter = ZaiProviderAdapter(providerId = "zai-1", apiKey = "k")

    /**
     * The moment the fixture was captured: 101h before its weekly reset.
     * Pinned so the credit range check is evaluated against a fixed clock
     * rather than a real one that would eventually move past the reset.
     */
    private val capturedAt = 1_790_634_590_984L - 101 * 3_600_000L

    /** Verbatim from the live endpoint. */
    private fun proAccountResponse() = ZaiQuotaResponse(
        success = true,
        data = ZaiQuotaData(
            limits = listOf(
                ZaiQuotaLimit(
                    type = "CREDIT_LIMIT",
                    unit = 3, number = 5,
                    usage = 12000, currentValue = 0, remaining = 12000,
                    percentage = 0,
                    nextResetTime = null,
                ),
                ZaiQuotaLimit(
                    type = "CREDIT_LIMIT",
                    unit = 6, number = 1,
                    usage = 60000, currentValue = 42292, remaining = 17707,
                    percentage = 70,
                    nextResetTime = 1_790_634_590_984L,
                ),
            ),
        ),
    )

    @Test
    fun aProAccountIsNoLongerUnavailable() {
        val report = adapter.mapToProviderReport(proAccountResponse(), capturedAt)

        assertTrue(report.available, "real quota was in the response and must be reported")
        assertEquals(2, report.windows.size)
    }

    @Test
    fun percentagesAreConvertedToRemaining() {
        val windows = adapter.mapToProviderReport(proAccountResponse(), capturedAt).windows

        // z.ai's `percentage` is consumed, not remaining.
        assertEquals(100, windows[0].remainingPct, "0% used -> 100% left")
        assertEquals(30, windows[1].remainingPct, "70% used -> 30% left")
    }

    @Test
    fun headlineIsTheMostDepletedWindowAndIsSelfConsistent() {
        val report = adapter.mapToProviderReport(proAccountResponse(), capturedAt)

        // The binding constraint is what stops work, so it leads.
        assertEquals(30, report.remainingPct)
        // %, reset and length must all come from that same window (PR #84).
        val source = report.windows.single { it.remainingPct == 30 }
        assertEquals(source.resetsAt, report.resetsAt)
        assertEquals(1_790_634_590_984L, report.resetsAt)
        assertEquals(168.0, report.windowHours, "and the length from that same window")
    }

    @Test
    fun periodsAreDecodedOntoThisProvidersKnownWindows() {
        // The unit encoding is undocumented, but the plan is not: a 5-hour
        // window and a weekly one, which is why those constants already exist
        // in this adapter. 5 hours + 1 week is the only reading consistent
        // with the two rows and with the weekly reset landing inside 168h.
        val report = adapter.mapToProviderReport(proAccountResponse(), capturedAt)

        assertEquals(5.0, report.windows[0].windowHours)
        assertEquals(168.0, report.windows[1].windowHours)
        assertEquals(listOf("5-hour", "Weekly"), report.windows.map { it.name })
    }

    @Test
    fun aDecodedPeriodIsRejectedWhenTheResetContradictsIt() {
        // The safety net: if the unit mapping is wrong, or z.ai changes it, a
        // reset falling outside the claimed window fails the range check and
        // the length drops to unknown rather than rendering a confidently
        // wrong hourglass.
        val report = adapter.mapToProviderReport(
            ZaiQuotaResponse(
                success = true,
                data = ZaiQuotaData(
                    limits = listOf(
                        ZaiQuotaLimit(
                            type = "CREDIT_LIMIT",
                            unit = 3, number = 5, // claims 5 hours
                            percentage = 20,
                            nextResetTime = 1_000_000L + 40L * 3_600_000, // 40h away
                        ),
                    ),
                ),
            ),
            now = 1_000_000L,
        )

        val window = report.windows.single()
        assertEquals(0.0, window.windowHours, "a 40h reset cannot belong to a 5h window")
        assertEquals(80, window.remainingPct, "the percentage is still good")
        assertEquals(1_000_000L + 40L * 3_600_000, window.resetsAt, "so is the reset")
    }

    @Test
    fun anUnknownUnitFallsBackToTheAllowanceLabelAndUnknownLength() {
        val report = adapter.mapToProviderReport(
            ZaiQuotaResponse(
                success = true,
                data = ZaiQuotaData(
                    limits = listOf(
                        ZaiQuotaLimit(
                            type = "CREDIT_LIMIT",
                            unit = 99, number = 2, // not a unit we account for
                            usage = 5000, percentage = 10,
                        ),
                    ),
                ),
            ),
            now = 1_000_000L,
        )

        val window = report.windows.single()
        assertEquals("5000 credits", window.name)
        assertEquals(0.0, window.windowHours)
        assertEquals(90, window.remainingPct)
    }

    @Test
    fun aMissingResetIsFlaggedEstimatedButKeepsItsPercentage() {
        val windows = adapter.mapToProviderReport(proAccountResponse(), capturedAt).windows

        assertNull(windows[0].resetsAt)
        assertTrue(windows[0].resetEstimated)
        assertEquals(100, windows[0].remainingPct, "a missing reset must not discard a good percentage")

        assertFalse(windows[1].resetEstimated)
    }

    @Test
    fun windowsAreNamedByPeriodWhenTheUnitDecodes() {
        // Matching the names the Claude Code plan adapter uses, so the two
        // provider cards read the same way.
        val names = adapter.mapToProviderReport(proAccountResponse(), capturedAt).windows.map { it.name }
        assertEquals(listOf("5-hour", "Weekly"), names)
    }

    @Test
    fun rawDisplaySummarisesOnlyTheBindingRow() {
        // Repeating every row duplicated what the bars and their labels
        // already show and read as noise.
        val raw = adapter.mapToProviderReport(proAccountResponse(), capturedAt).rawDisplay
        assertEquals("42292 of 60000 credits used", raw)
    }

    @Test
    fun aSingleCreditRowIsNamedPlainly() {
        val report = adapter.mapToProviderReport(
            ZaiQuotaResponse(
                success = true,
                data = ZaiQuotaData(
                    limits = listOf(ZaiQuotaLimit(type = "CREDIT_LIMIT", percentage = 40)),
                ),
            ),
        )
        assertEquals("Credits", report.windows.single().name)
        assertEquals(60, report.windows.single().remainingPct)
    }

    @Test
    fun tokenAccountsAreUnaffected() {
        // The original shape must keep its existing windows and headline —
        // credits are a fallback, not a replacement.
        val report = adapter.mapToProviderReport(
            ZaiQuotaResponse(
                success = true,
                data = ZaiQuotaData(
                    limits = listOf(
                        ZaiQuotaLimit(type = "TOKENS_LIMIT", percentage = 42, nextResetTime = 1_000L),
                        ZaiQuotaLimit(type = "SESSION_LIMIT", percentage = 10, nextResetTime = 2_000L),
                        // Even if a credit row rides along, it must not take over.
                        ZaiQuotaLimit(type = "CREDIT_LIMIT", percentage = 99),
                    ),
                ),
            ),
        )

        assertEquals(58, report.remainingPct, "TOKENS_LIMIT still leads")
        assertEquals(5.0, report.windowHours)
        assertEquals(2, report.windows.size, "no credit window is added when tokens are present")
        assertTrue(report.rawDisplay.contains("tokens:42%"), report.rawDisplay)
    }

    @Test
    fun anEmptyLimitsArrayIsStillUnavailable() {
        val report = adapter.mapToProviderReport(
            ZaiQuotaResponse(success = true, data = ZaiQuotaData(limits = emptyList())),
        )
        assertFalse(report.available)
        assertNull(report.remainingPct)
    }
}
