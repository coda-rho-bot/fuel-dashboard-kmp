package com.angussoftware.fueldashboard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun runJunieCredits(): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder("junie-credits")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() != 0) {
        throw IllegalStateException(output.ifBlank { "junie-credits failed" })
    }
    output
}

internal actual val canCheckJunieBalance: Boolean = true