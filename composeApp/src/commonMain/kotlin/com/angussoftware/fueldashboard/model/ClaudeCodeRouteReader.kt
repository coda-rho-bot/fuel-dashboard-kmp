package com.angussoftware.fueldashboard.model

/**
 * Reads Claude Code's current routing from local configuration.
 *
 * Returns null when it cannot be determined — not installed, unreadable, or
 * an unsupported platform. Null means "unknown", never "stock Anthropic":
 * the caller must be able to tell those apart, since an absent
 * ANTHROPIC_BASE_URL is itself a positive answer.
 */
internal expect fun readClaudeCodeRoute(): ClaudeCodeRoute?
