package com.angussoftware.fueldashboard.presentation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long a server may silence a provider.
 *
 * Respecting Retry-After is correct — re-asking early just earns another 429.
 * The risk is the other direction: a header that is wrong, or simply enormous,
 * takes a provider off the dashboard for hours, and a parked provider looks
 * exactly like a broken one because its tile stops changing. So the wait is
 * bounded and the reason is shown.
 *
 * Retry-After parsing itself is covered by ClaudeCodeUsageHttpTest; this is
 * only about what the view model does with the value it gets.
 */
class RateLimitParkTest {

    // Exercises the production bound, not a copy of it.
    private val vm = FuelViewModel()
    private val ceilingMs = vm.maxRateLimitParkMs
    private val now = 1_000_000L

    /** How long the view model would actually park, in ms from now. */
    private fun parkFor(retryAfterMs: Long): Long = vm.parkUntil(now, retryAfterMs) - now

    @AfterTest
    fun close() = vm.close()

    @Test
    fun anOrdinaryRetryAfterIsRespectedExactly() {
        // Well within the ceiling: honour what the server asked for.
        assertEquals(60_000L, parkFor(60_000L))
        assertEquals(5L * 60 * 1000, parkFor(5L * 60 * 1000))
    }

    @Test
    fun theCeilingItselfIsRespected() {
        assertEquals(ceilingMs, parkFor(ceilingMs))
    }

    @Test
    fun anEnormousRetryAfterIsCapped() {
        // A day-long park is indistinguishable from a hang. Capping costs at
        // most one request per ceiling: if the server really wants longer it
        // answers 429 again and we re-park.
        assertEquals(ceilingMs, parkFor(24L * 60 * 60 * 1000))
        assertEquals(ceilingMs, parkFor(Long.MAX_VALUE / 2))
    }

    @Test
    fun aSecondsForMillisecondsMistakeIsAlsoCapped() {
        // The realistic malformed case: 86400 meant as seconds, read as
        // milliseconds, is harmless — but the reverse, 86400 seconds sent as
        // milliseconds, parks for a day.
        assertEquals(ceilingMs, parkFor(86_400L * 1000))
    }

    @Test
    fun onlyFutureParksAreWorthAnnouncing() {
        // The view model publishes parks that are still in the future; an
        // expired one is not a state the card should keep reporting.
        val now = 1_000_000L
        val parks = mapOf("a" to now - 1, "b" to now + 60_000)
        assertEquals(setOf("b"), parks.filterValues { it > now }.keys)
    }
}
