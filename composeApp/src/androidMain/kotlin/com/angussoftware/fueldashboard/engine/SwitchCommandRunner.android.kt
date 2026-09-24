package com.angussoftware.fueldashboard.engine

/** Android cannot spawn arbitrary processes; switch commands are desktop-only. */
internal actual suspend fun runSwitchCommand(
    command: String,
    timeoutMs: Long,
): SwitchCommandResult = SwitchCommandResult(
    exitCode = null,
    output = "switch commands run on the desktop dashboard only",
)

internal actual val switchCommandsSupported: Boolean = false
