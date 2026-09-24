package com.angussoftware.fueldashboard.model

/**
 * What Claude Code's own live-session registry says about the fleet.
 *
 * Claude Code writes one file per running session under `~/.claude/sessions/`
 * carrying a `status` of `busy` or `idle`. That is the authoritative idle
 * signal — more so than anything this app could infer from polling — and it
 * is what makes it safe to act.
 *
 * This exists because a switch command is destructive: the one that motivated
 * it respawns every pane, which kills whatever turn is in flight. Completed
 * turns are durable on disk; the live one is not. So an action must wait for
 * quiet rather than interrupt.
 */
data class ClaudeCodeFleet(
    val busy: Int = 0,
    val idle: Int = 0,
    /** Sessions whose status could not be read or looked stale. */
    val unknown: Int = 0,
) {
    val total: Int get() = busy + idle + unknown

    /**
     * True only when every session is accounted for and none is working.
     *
     * `unknown` counts against quiet deliberately: a session we cannot read
     * might be mid-turn, and the cost of guessing wrong is a killed turn. An
     * empty fleet is not quiet either — it means the registry told us
     * nothing, which is not the same as "nobody is working".
     */
    val isQuiet: Boolean get() = total > 0 && busy == 0 && unknown == 0

    fun describe(): String = "busy=$busy idle=$idle unknown=$unknown"
}

/**
 * Reads the live-session registry.
 *
 * Returns null when the registry cannot be read at all — not installed, or an
 * unsupported platform — which the caller must treat as "unknown", never as
 * "quiet".
 */
internal expect fun readClaudeCodeFleet(): ClaudeCodeFleet?
