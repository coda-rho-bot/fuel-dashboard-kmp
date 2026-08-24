package com.angussoftware.fueldashboard.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [FeedbackSubmitter] — the offline-testable surface:
 * blank-token guard, created-issue response parsing, and HTTP-failure
 * message mapping. The network POST itself is exercised live by usage.
 */
class FeedbackSubmitterTest {

    @Test
    fun blankTokenFailsImmediatelyWithoutNetwork() = kotlinx.coroutines.test.runTest {
        val result = FeedbackSubmitter.submit(
            forgejoUrl = "https://git.angussoftware.dev",
            repo = "coda/fuel-dashboard-kmp",
            token = "",
            title = "t",
            body = "b",
        )
        assertTrue(result is FeedbackSubmitter.Result.Failure)
        assertEquals("Feedback token is missing.", (result as FeedbackSubmitter.Result.Failure).message)
    }

    @Test
    fun parseSuccessReadsIssueUrlAndNumber() {
        val result = FeedbackSubmitter.parseSuccess(
            """{"html_url":"https://git.angussoftware.dev/coda/fuel-dashboard-kmp/issues/42","number":42}""",
            forgejoUrl = "https://git.angussoftware.dev",
            repo = "coda/fuel-dashboard-kmp",
        )
        assertEquals(
            FeedbackSubmitter.Result.Success(
                url = "https://git.angussoftware.dev/coda/fuel-dashboard-kmp/issues/42",
                number = 42,
            ),
            result,
        )
    }

    @Test
    fun parseSuccessFallsBackWhenFieldsMissing() {
        // Malformed-but-successful response must not crash — fallback URL,
        // sentinel number.
        val result = FeedbackSubmitter.parseSuccess(
            """{}""",
            forgejoUrl = "https://git.angussoftware.dev/",
            repo = "coda/fuel-dashboard-kmp",
        )
        assertEquals(
            "https://git.angussoftware.dev/coda/fuel-dashboard-kmp/issues",
            (result as FeedbackSubmitter.Result.Success).url,
        )
        assertEquals(-1, result.number)
    }

    @Test
    fun mapFailure401AdvisesResync() {
        val result = FeedbackSubmitter.mapFailure(401, "unauthorized")
        assertEquals(
            "Feedback token is invalid (401) — re-sync settings from the main dashboard.",
            (result as FeedbackSubmitter.Result.Failure).message,
        )
    }

    @Test
    fun mapFailure403AdvisesResync() {
        val result = FeedbackSubmitter.mapFailure(403, "forbidden")
        assertEquals(
            "Feedback token was rejected (403) — re-sync settings from the main dashboard.",
            (result as FeedbackSubmitter.Result.Failure).message,
        )
    }

    @Test
    fun mapFailureOtherShowsStatusAndBody() {
        val result = FeedbackSubmitter.mapFailure(500, "kaboom")
        assertEquals(
            "HTTP 500: kaboom",
            (result as FeedbackSubmitter.Result.Failure).message,
        )
    }
}
