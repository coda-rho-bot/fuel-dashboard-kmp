package com.angussoftware.fueldashboard.ui.components

import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun formatCountdown(resetsAtEpochMs: Long, nowMs: Long = epochMillis()): String {
    val diff = resetsAtEpochMs - nowMs

    if (diff <= 0L) return "resetting..."

    val totalMinutes = diff / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L

    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatLastSeen(epochMs: Long, nowMs: Long = epochMillis()): String {
    val minutes = (nowMs - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

fun formatLastUpdated(epochMs: Long): String {
    if (epochMs == 0L) return ""
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "Updated ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
}