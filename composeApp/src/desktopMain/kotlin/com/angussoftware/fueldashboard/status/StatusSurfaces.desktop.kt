package com.angussoftware.fueldashboard.status

import androidx.compose.runtime.mutableStateOf
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting

/**
 * Desktop: the persistent surface is the HUD mini-window. The settings
 * toggle flips [hudVisible] — main.kt's Window reacts to it, so the window
 * appears/disappears live without restart.
 */
class DesktopStatusSurfaces : StatusSurfaces {
    override val label: String = "Status HUD"
    override val supported: Boolean = true
    override val supportsAlwaysOnTopToggle: Boolean = true

    override fun setEnabled(enabled: Boolean) {
        saveStringSetting(FuelSettingsKeys.HUD_ENABLED, enabled.toString())
        hudVisible.value = enabled
    }

    override fun isEnabled(): Boolean =
        loadStringSetting(FuelSettingsKeys.HUD_ENABLED, "false").toBoolean()

    override fun setAlwaysOnTop(enabled: Boolean) {
        saveStringSetting(FuelSettingsKeys.HUD_ALWAYS_ON_TOP, enabled.toString())
        hudAlwaysOnTop.value = enabled
    }

    override fun alwaysOnTop(): Boolean =
        loadStringSetting(FuelSettingsKeys.HUD_ALWAYS_ON_TOP, "true").toBoolean()

    companion object {
        /** Observed by main.kt to show/hide the HUD window. */
        val hudVisible = mutableStateOf(
            loadStringSetting(FuelSettingsKeys.HUD_ENABLED, "false").toBoolean(),
        )

        /** Observed by main.kt to pin/unpin the HUD window live. */
        val hudAlwaysOnTop = mutableStateOf(
            loadStringSetting(FuelSettingsKeys.HUD_ALWAYS_ON_TOP, "true").toBoolean(),
        )
    }
}

private val instance = DesktopStatusSurfaces()

actual fun statusSurfaces(): StatusSurfaces = instance
