package com.angussoftware.fueldashboard.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal actual suspend fun runSwitchCommand(
    command: String,
    timeoutMs: Long,
): SwitchCommandResult = withContext(Dispatchers.IO) {
    val argv = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (argv.isEmpty()) {
        return@withContext SwitchCommandResult(exitCode = null, output = "empty command")
    }

    val process = try {
        ProcessBuilder(argv).redirectErrorStream(true).start()
    } catch (e: Exception) {
        // Most often the binary is not on the app's PATH, which differs from
        // a login shell's when launched from a desktop entry.
        return@withContext SwitchCommandResult(
            exitCode = null,
            output = "${e::class.simpleName}: ${e.message}",
        )
    }

    try {
        // Read before waiting: a command that fills the pipe buffer would
        // block forever if we waited first.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@withContext SwitchCommandResult(exitCode = null, output = output, timedOut = true)
        }
        SwitchCommandResult(exitCode = process.exitValue(), output = output)
    } catch (e: Exception) {
        process.destroyForcibly()
        SwitchCommandResult(exitCode = null, output = "${e::class.simpleName}: ${e.message}")
    }
}

internal actual val switchCommandsSupported: Boolean = true
