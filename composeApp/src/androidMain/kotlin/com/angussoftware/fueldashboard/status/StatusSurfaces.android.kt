package com.angussoftware.fueldashboard.status

import android.content.Context
import com.angussoftware.fueldashboard.FuelStatusService
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting

/**
 * Android: the persistent surface is the foreground-service notification.
 * The service is started/stopped via application context.
 */
class AndroidStatusSurfaces(private val appContext: Context) : StatusSurfaces {
    override val label: String = "Persistent status notification"
    override val supported: Boolean = true
    override val supportsIconToggle: Boolean = true

    override fun setEnabled(enabled: Boolean) {
        saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, enabled.toString())
        if (enabled) {
            FuelStatusService.start(appContext)
        } else {
            FuelStatusService.stop(appContext)
        }
    }

    override fun isEnabled(): Boolean = FuelStatusService.isEnabled()

    override fun setShowIcon(show: Boolean) {
        saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_SHOW_ICON, show.toString())
        // Restart the service so it picks up the new icon
        if (isEnabled()) {
            FuelStatusService.stop(appContext)
            FuelStatusService.start(appContext)
        }
    }

    override fun showIcon(): Boolean =
        loadStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_SHOW_ICON, "true").toBoolean()
}

private var instance: StatusSurfaces? = null

actual fun statusSurfaces(): StatusSurfaces =
    instance ?: error("AndroidStatusSurfaces not initialized — call initStatusSurfaces(context) in Application.onCreate()")

fun initStatusSurfaces(appContext: Context) {
    if (instance == null) instance = AndroidStatusSurfaces(appContext)
}
