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

    override fun setEnabled(enabled: Boolean) {
        saveStringSetting(FuelSettingsKeys.HUD_ENABLED, enabled.toString())
        hudVisible.value = enabled
    }

    override fun isEnabled(): Boolean =
        loadStringSetting(FuelSettingsKeys.HUD_ENABLED, "false").toBoolean()

    companion object {
        /** Observed by main.kt to show/hide the HUD window. */
        val hudVisible = mutableStateOf(
            loadStringSetting(FuelSettingsKeys.HUD_ENABLED, "false").toBoolean(),
        )
    }
}

private val instance = DesktopStatusSurfaces()

actual fun statusSurfaces(): StatusSurfaces = instance
