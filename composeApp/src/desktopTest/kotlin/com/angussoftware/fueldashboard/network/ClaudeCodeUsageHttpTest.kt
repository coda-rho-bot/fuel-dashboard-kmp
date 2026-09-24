package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A non-2xx from the usage endpoint must become a visible failure.
 *
 * It did not. Ktor defaults `expectSuccess = false`, and every field of
 * [com.angussoftware.fueldashboard.model.ClaudeCodeUsageResponse] has a
 * default, so an error body deserialized cleanly into an EMPTY response.
 * A 429 therefore rendered as "this plan reports no data" — and because
 * nothing threw, the exponential failure backoff never engaged, so the
 * dashboard kept polling the endpoint that was already refusing it, every
 * 30 seconds, keeping itself throttled.
 */
class ClaudeCodeUsageHttpTest {

    @Test
    fun successIsNotAFailure() {
        assertNull(failureFor(HttpStatusCode.OK, null))
        assertNull(failureFor(HttpStatusCode.NoContent, null))
    }

    @Test
    fun rateLimitSaysSoInsteadOfLookingEmpty() {
        val e = assertNotNull(failureFor(HttpStatusCode.TooManyRequests, null))
        assertTrue(
            e.message!!.contains("Rate limited", ignoreCase = true),
            "a 429 must name itself, not read as missing data: ${e.message}",
        )
    }

    @Test
    fun retryAfterIsCarriedInSecondsToMillis() {
        val e = assertNotNull(failureFor(HttpStatusCode.TooManyRequests, "120"))
        assertEquals(120_000L, e.retryAfterMs)
        assertTrue(e.message!!.contains("120s"), "should tell the user the wait: ${e.message}")
    }

    @Test
    fun aMissingOrJunkRetryAfterIsSimplyAbsent() {
        // An HTTP-date Retry-After, or nonsense, must not become a bogus park.
        assertNull(assertNotNull(failureFor(HttpStatusCode.TooManyRequests, "Wed, 21 Oct 2026 07:28:00 GMT")).retryAfterMs)
        assertNull(assertNotNull(failureFor(HttpStatusCode.TooManyRequests, "")).retryAfterMs)
        assertNull(assertNotNull(failureFor(HttpStatusCode.TooManyRequests, null)).retryAfterMs)
    }

    @Test
    fun authFailuresPointAtLoginRatherThanQuota() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val e = assertNotNull(failureFor(status, null))
            assertTrue(
                e.message!!.contains("/login"),
                "an auth failure should say how to fix it: ${e.message}",
            )
        }
    }

    @Test
    fun serverErrorsAreReportedAsTheirs() {
        val e = assertNotNull(failureFor(HttpStatusCode.BadGateway, null))
        assertTrue(e.message!!.contains("502"), "should carry the status: ${e.message}")
    }

    /** Claude Code's windows are hours and days wide; 30s polling only earns a 429. */
    @Test
    fun claudeCodeIsNotPolledFasterThanItsWindowsCanMove() {
        val vm = FuelViewModel()
        assertEquals(300, vm.minPollIntervalSec(ProviderKind.CLAUDE_CODE))
        assertTrue(
            vm.minPollIntervalSec(ProviderKind.ZAI) < 300,
            "the floor is specific to the endpoint that rate-limits, not a global slowdown",
        )
    }

    private fun assertNotNull(e: ClaudeCodeUsageHttpException?): ClaudeCodeUsageHttpException {
        kotlin.test.assertNotNull(e)
        return e
    }
}
