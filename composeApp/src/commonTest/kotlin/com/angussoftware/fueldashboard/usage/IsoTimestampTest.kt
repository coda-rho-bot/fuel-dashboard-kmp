package com.angussoftware.fueldashboard.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoTimestampTest {
    // Verified reference: 2026-08-14T16:20:10Z == 1786724410 s
    @Test
    fun parsesUtcZuluWithMillis() {
        assertEquals(1786724410899L, parseIsoMillis("2026-08-14T16:20:10.899Z"))
    }

    @Test
    fun parsesUtcZuluWithoutMillis() {
        assertEquals(1786724410000L, parseIsoMillis("2026-08-14T16:20:10Z"))
    }

    @Test
    fun parsesExplicitOffset() {
        // Same instant as 16:20:10Z expressed at -05:00
        assertEquals(1786724410000L, parseIsoMillis("2026-08-14T11:20:10-05:00"))
        // And at +00:00
        assertEquals(1786724410000L, parseIsoMillis("2026-08-14T16:20:10+00:00"))
    }

    @Test
    fun parsesOffsetWithoutColon() {
        assertEquals(1786724410000L, parseIsoMillis("2026-08-14T11:20:10-0500"))
    }

    @Test
    fun handlesFractionPadding() {
        // 1 digit → 100ms, 9 digits → nanosecond precision truncated to millis
        assertEquals(1786724410100L, parseIsoMillis("2026-08-14T16:20:10.1Z"))
        assertEquals(1786724410999L, parseIsoMillis("2026-08-14T16:20:10.999999999Z"))
    }

    @Test
    fun handlesEpochBoundary() {
        assertEquals(0L, parseIsoMillis("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, parseIsoMillis("2000-02-29T00:00:00Z")) // leap day
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseIsoMillis("not a timestamp"))
        assertNull(parseIsoMillis("2026-08-14"))
        assertNull(parseIsoMillis(""))
    }
}
