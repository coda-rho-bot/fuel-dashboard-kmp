package com.angussoftware.fueldashboard.network

/**
 * Android has no access to Claude Code's credentials file — the CLI does not run
 * here. The plan gauge reaches this device the same way every other provider's
 * does: through a Remote Dashboard pointed at the desktop app.
 */
internal actual fun readClaudeCodeOAuthToken(): String? = null

internal actual val claudeCodeCredentialsUnavailableHint: String =
    "Claude Code plan usage is read on the desktop dashboard — " +
        "connect to it to see this gauge here."
