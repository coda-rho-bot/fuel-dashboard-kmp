package com.angussoftware.fueldashboard.util

/**
 * Format a string with [java.util.Locale.ROOT] semantics so that numbers
 * always use a dot decimal separator regardless of the device locale.
 *
 * On JVM targets (desktop, Android) this delegates to
 * `String.format(Locale.ROOT, ...)`. On Kotlin/Native (iOS) the default
 * `String.format` already uses C-locale (dot separator), so it is safe.
 */
expect fun formatRoot(pattern: String, vararg args: Any?): String
