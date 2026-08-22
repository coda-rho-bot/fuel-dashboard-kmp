package com.angussoftware.fueldashboard.status

/**
 * iOS: persistent status surface deferred (issue #60 — Live Activities).
 * The settings toggle stays hidden while unsupported.
 */
class IosStatusSurfaces : StatusSurfaces {
    override val label: String = "Status"
    override val supported: Boolean = false
    override fun setEnabled(enabled: Boolean) = Unit
    override fun isEnabled(): Boolean = false
}

private val instance = IosStatusSurfaces()

actual fun statusSurfaces(): StatusSurfaces = instance
