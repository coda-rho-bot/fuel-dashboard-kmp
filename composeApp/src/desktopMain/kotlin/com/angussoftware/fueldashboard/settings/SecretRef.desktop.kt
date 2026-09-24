package com.angussoftware.fueldashboard.settings

import java.io.File
import java.util.concurrent.TimeUnit

/** How long a helper command may take before it is treated as unresolvable. */
private const val COMMAND_TIMEOUT_SECONDS = 20L

/** Credentials are short; a helper streaming past this is misbehaving, not producing a key. */
private const val MAX_CREDENTIAL_BYTES = 8 * 1024

/**
 * Resolves a credential reference on desktop.
 *
 * Resolved values are returned to the caller and nothing else: they are never
 * logged, never written to settings, and not cached here. Re-resolving on each
 * use is deliberate — it is what lets a rotated secret or a freshly unlocked
 * vault take effect without restarting the app.
 */
internal actual fun resolveSecretRef(ref: SecretRef): String? = when (ref) {
    is SecretRef.Literal -> ref.value

    is SecretRef.Environment ->
        System.getenv(ref.name)?.trim()?.ifEmpty { null }

    is SecretRef.FileContents -> runCatching {
        File(expandHome(ref.path)).takeIf { it.isFile && it.canRead() }
            ?.readText()?.trim()?.ifEmpty { null }
    }.getOrNull()

    is SecretRef.Command -> runCommandForSecret(ref.command)
}

/**
 * Runs a helper and takes its stdout as the credential.
 *
 * stderr is deliberately NOT merged into stdout: helpers print diagnostics
 * there ("vault is sealed", "run /login"), and folding that into the returned
 * value would send an error message to the provider as an API key.
 */
private fun runCommandForSecret(command: String): String? {
    val argv = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (argv.isEmpty()) return null

    val process = runCatching {
        ProcessBuilder(listOf(expandHome(argv.first())) + argv.drop(1))
            .redirectErrorStream(false)
            .start()
    }.getOrNull() ?: return null

    return try {
        // Bounded tail-read on a separate thread: a helper that streams
        // forever would otherwise block past the timeout (readText() ends
        // only at EOF), and an unbounded read would blow the heap before the
        // watchdog fires. Credentials are short — cap the retained bytes.
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val outputFuture = reader.submit<String> {
            val br = process.inputStream.bufferedReader()
            val sb = StringBuilder()
            while (true) {
                val line = br.readLine() ?: break
                sb.appendLine(line)
                if (sb.length > MAX_CREDENTIAL_BYTES) break
            }
            sb.toString()
        }
        val timedOut = !process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (timedOut) {
            process.destroyForcibly()
            reader.shutdownNow()
            return null
        }
        val output = try {
            outputFuture.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ""
        } finally {
            reader.shutdown()
        }
        // A non-zero exit means the helper could not produce a credential; its
        // partial stdout must not be mistaken for one.
        if (process.exitValue() != 0) null else output.trim().ifEmpty { null }
    } catch (e: Exception) {
        process.destroyForcibly()
        null
    }
}

/** Expands a leading `~` so paths can be written the way a shell accepts them. */
private fun expandHome(path: String): String =
    if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path

internal actual val secretCommandsSupported: Boolean = true
