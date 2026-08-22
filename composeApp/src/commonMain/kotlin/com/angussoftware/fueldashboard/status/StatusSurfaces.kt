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

    /** Whether this platform supports a "show icon in status bar" toggle. */
    val supportsIconToggle: Boolean get() = false

    /** Whether this platform supports an "always on top" toggle. */
    val supportsAlwaysOnTopToggle: Boolean get() = false

    /** Apply the new enabled state. */
    fun setEnabled(enabled: Boolean)

    /** Current enabled state (read from settings). */
    fun isEnabled(): Boolean

    /** Set whether the status bar icon is shown (Android only). */
    fun setShowIcon(show: Boolean) {}

    /** Current show-icon state (Android only, default true). */
    fun showIcon(): Boolean = true

    /** Set whether the surface floats above other windows (desktop only). */
    fun setAlwaysOnTop(enabled: Boolean) {}

    /** Current always-on-top state (desktop only, default true). */
    fun alwaysOnTop(): Boolean = true
}

/** Platform-provided singleton. */
expect fun statusSurfaces(): StatusSurfaces
