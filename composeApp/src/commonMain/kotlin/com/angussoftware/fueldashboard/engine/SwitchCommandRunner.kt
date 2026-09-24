package com.angussoftware.fueldashboard.engine

/**
 * Outcome of running a provider's switch command.
 *
 * [exitCode] is null when the command could not be run at all (not found,
 * timed out, unsupported platform) as opposed to running and failing —
 * the decision log distinguishes the two.
 */
data class SwitchCommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
) {
    val succeeded: Boolean get() = exitCode == 0

    /** One line for the decision log; long output is truncated. */
    fun summary(): String = when {
        timedOut -> "timed out"
        exitCode == null -> "could not run: ${output.trim().take(160)}"
        exitCode == 0 -> "ok" + output.trim().takeIf { it.isNotEmpty() }
            ?.let { ": ${it.lines().last().take(160)}" }.orEmpty()
        else -> "exit $exitCode: ${output.trim().lines().lastOrNull()?.take(160).orEmpty()}"
    }
}

/**
 * Runs a provider's configured switch command.
 *
 * The command is split on whitespace and executed directly — there is no
 * shell, so pipes, redirects and globs are not interpreted. That keeps a
 * settings string from becoming an injection surface and makes
 * `my-script --provider backup` mean exactly what it looks like. A caller
 * needing shell semantics should point this at a script.
 *
 * Implementations must never throw: every failure comes back as a
 * [SwitchCommandResult] so the outcome can be recorded rather than lost.
 */
internal expect suspend fun runSwitchCommand(
    command: String,
    timeoutMs: Long = 120_000,
): SwitchCommandResult

/** Whether this platform can execute a switch command at all. */
internal expect val switchCommandsSupported: Boolean
