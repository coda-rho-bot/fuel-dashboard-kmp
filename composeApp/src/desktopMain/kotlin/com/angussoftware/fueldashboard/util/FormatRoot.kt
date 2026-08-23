package com.angussoftware.fueldashboard.util

import java.util.Locale

actual fun formatRoot(pattern: String, vararg args: Any?): String =
    String.format(Locale.ROOT, pattern, *args)
