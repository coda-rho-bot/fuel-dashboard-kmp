package com.angussoftware.fueldashboard.usage

/**
 * Minimal ISO-8601 timestamp parser (pure Kotlin, commonMain).
 *
 * Handles the formats the Letta API emits: "2026-08-14T16:20:10.899Z"
 * and offsets like "+00:00" / "-05:00". Fractional seconds may have 1-9
 * digits. Returns epoch milliseconds, or null if unparseable.
 */
internal fun parseIsoMillis(input: String): Long? {
    val regex = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|z|[+-]\d{2}:?\d{2})?$"""
    )
    val m = regex.matchEntire(input.trim()) ?: return null
    val (year, month, day, hour, minute, second) = m.destructured

    val fraction = m.groupValues[7].let { raw ->
        when {
            raw.isEmpty() -> 0L
            else -> (raw + "000000000").substring(0, 9).toLong() / 1_000_000 // → millis
        }
    }

    val offsetMillis = when (val zone = m.groupValues[8]) {
        "", "Z", "z" -> 0L
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            val hh = zone.substring(1, 3).toInt()
            val mm = when (zone.length) {
                6 -> zone.substring(4, 6).toInt() // +HH:MM
                5 -> zone.substring(3, 5).toInt() // +HHMM
                else -> 0                          // +HH
            }
            sign * (hh * 3600 + mm * 60) * 1000L
        }
    }

    val days = daysFromCivil(year.toInt(), month.toInt(), day.toInt())
    val secs = days * 86_400L +
        hour.toInt() * 3600L +
        minute.toInt() * 60L +
        second.toInt()
    return secs * 1000L + fraction - offsetMillis
}

/** Days since 1970-01-01 from a civil date (Howard Hinnant's algorithm). */
private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
