package com.angussoftware.fueldashboard.util

/**
 * iOS (Kotlin/Native): `String.format` uses C-locale (dot decimal separator)
 * by default, so no explicit Locale is needed.
 */
actual fun formatRoot(pattern: String, vararg args: Any?): String =
    String.format(pattern, *args)
