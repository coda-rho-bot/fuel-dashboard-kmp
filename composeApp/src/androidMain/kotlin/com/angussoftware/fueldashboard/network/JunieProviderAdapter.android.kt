package com.angussoftware.fueldashboard.network

internal actual suspend fun runJunieCredits(): String {
    throw UnsupportedOperationException("Junie balance checks are available on desktop only")
}

internal actual val canCheckJunieBalance: Boolean = false

/** The Junie CLI can't run on a phone — point users at the desktop app. */
internal actual val junieCheckUnavailableHint: String =
    "Balance checks run on the desktop dashboard — connect to it to see the latest Junie balance here."