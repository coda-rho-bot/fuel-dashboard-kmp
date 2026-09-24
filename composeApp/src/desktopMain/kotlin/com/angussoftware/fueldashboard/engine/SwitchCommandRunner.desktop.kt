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
        // Bounded tail-read on a separate thread. Two hazards for streaming
        // commands (`yes`):
        //  1. readText() blocks until EOF, which only comes when the process
        //     dies — so the timeout watchdog must be able to kill the process
        //     without waiting for the read (hence the separate thread).
        //  2. An unbounded read accumulates output at pipe speed and blows the
        //     heap before the timeout fires (the OOM closes the stream, the
        //     command SIGPIPEs, and the run is misreported as a normal exit).
        // Keeping only the last MAX_OUTPUT_BYTES preserves what summary()
        // actually uses (the tail) while draining the pipe so the process
        // never blocks on a full buffer.
        val outputReader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val outputFuture = outputReader.submit<String> {
            val br = process.inputStream.bufferedReader()
            val lines = ArrayDeque<String>()
            var total = 0
            while (true) {
                val line = br.readLine() ?: break
                lines.addLast(line)
                total += line.length + 1
                while (total > MAX_OUTPUT_BYTES && lines.size > 1) {
                    total -= lines.removeFirst().length + 1
                }
            }
            lines.joinToString("\n")
        }
        val timedOut = !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (timedOut) {
            process.destroyForcibly()
        }
        val output = try {
            // Bound the post-kill drain too: destroyForcibly should end the
            // stream promptly, but don't wait on a rogue grandchild process.
            outputFuture.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            outputReader.shutdownNow()
            ""
        } finally {
            outputReader.shutdown()
        }
        if (timedOut) {
            return@withContext SwitchCommandResult(exitCode = null, output = output, timedOut = true)
        }
        SwitchCommandResult(exitCode = process.exitValue(), output = output)
    } catch (e: Exception) {
        process.destroyForcibly()
        SwitchCommandResult(exitCode = null, output = "${e::class.simpleName}: ${e.message}")
    }
}

/** Cap on retained command output; only the tail is ever displayed. */
private const val MAX_OUTPUT_BYTES = 64 * 1024

internal actual val switchCommandsSupported: Boolean = true
