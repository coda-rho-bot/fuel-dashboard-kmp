package com.angussoftware.fueldashboard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual suspend fun runJunieCredits(): String = withContext(Dispatchers.IO) {
    val script = extractBundledScript()
    val process = ProcessBuilder("python3", script.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() != 0) {
        throw IllegalStateException(output.ifBlank { "junie-credits script failed" })
    }
    output
}

/**
 * Extracts the bundled junie-credits.py to a temp file and returns its path.
 * The script is shipped as a resource inside the app.
 */
private fun extractBundledScript(): File {
    val tempFile = File.createTempFile("junie-credits", ".py")
    tempFile.deleteOnExit()
    val resource = Thread.currentThread().contextClassLoader
        .getResourceAsStream("junie-credits.py")
        ?: throw IllegalStateException("Bundled junie-credits.py not found")
    resource.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}

/**
 * Whether this platform can check the Junie balance.
 *
 * Requires: python3 with pexpect, the Junie CLI, and ~/.junie/auth.
 * The junie-credits.py script is bundled with the app.
 */
internal actual val canCheckJunieBalance: Boolean =
    isBinaryInPath("python3") && hasJunieCli()

/**
 * Checks if the Junie CLI (junie or junie-auth) is available.
 */
internal fun hasJunieCli(): Boolean {
    return isBinaryInPath("junie-auth") || isBinaryInPath("junie") || run {
        val home = System.getProperty("user.home")
        File("$home/.local/bin/junie-auth").exists() || File("$home/.local/bin/junie").exists()
    }
}

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
