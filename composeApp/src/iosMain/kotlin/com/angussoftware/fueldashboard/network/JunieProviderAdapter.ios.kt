package com.angussoftware.fueldashboard.network

internal actual suspend fun runJunieCredits(): String {
    throw UnsupportedOperationException("Junie balance checks are available on desktop only")
}

internal actual val canCheckJunieBalance: Boolean = false