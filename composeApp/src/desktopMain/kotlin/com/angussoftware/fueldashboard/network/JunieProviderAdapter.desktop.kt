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

/**
 * Checks whether the `junie-credits` helper script is available in PATH.
 *
 * The Junie CLI itself (`junie`) does not expose balance information non-interactively,
 * so the `junie-credits` helper script is required for balance checking.
 */
internal actual val canCheckJunieBalance: Boolean = isBinaryInPath("junie-credits")

/**
 * Returns true if the given binary name is found in the system PATH.
 */
internal fun isBinaryInPath(binaryName: String): Boolean {
    return try {
        val checkCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("where", binaryName)
        } else {
            listOf("which", binaryName)
        }
        val proc = ProcessBuilder(checkCmd)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}