package com.angussoftware.fueldashboard.status

/**
 * Platform surface controls for persistent status:
 * - Android: foreground-service notification
 * - Desktop: HUD mini-window
 * - iOS: deferred (issue #60)
 *
 * The common Settings page renders ONE toggle and delegates the effect here;
 * each platform's actual knows whether it supports a persistent surface.
 */
interface StatusSurfaces {
    /** Human label for the settings toggle, e.g. "Persistent status notification". */
    val label: String

    /** Whether this platform has a persistent status surface at all. */
    val supported: Boolean

    /** Apply the new enabled state. */
    fun setEnabled(enabled: Boolean)

    /** Current enabled state (read from settings). */
    fun isEnabled(): Boolean
}

/** Platform-provided singleton. */
expect fun statusSurfaces(): StatusSurfaces
