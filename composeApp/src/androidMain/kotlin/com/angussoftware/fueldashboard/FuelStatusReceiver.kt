package com.angussoftware.fueldashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.saveStringSetting

/**
 * Receiver for the persistent-notification stop action and for device boot.
 *
 * Stop (both service and worker modes): disables the setting, stops the
 * foreground service if running, cancels the background worker, and removes
 * the notification. One code path for both modes.
 *
 * Boot: apps targeting Android 15+ may NOT start a dataSync foreground
 * service from BOOT_COMPLETED, so restore runs in worker mode — the periodic
 * worker wakes within ~15 minutes and re-posts the notification. When the
 * user opens the app, MainActivity promotes back to the foreground service.
 */
class FuelStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FuelNotification.ACTION_STOP -> {
                saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false")
                context.stopService(Intent(context, FuelStatusService::class.java))
                FuelStatusWorker.cancel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(FuelNotification.NOTIFICATION_ID)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                if (FuelStatusService.isEnabled()) {
                    // Worker mode only: dataSync FGS starts from
                    // BOOT_COMPLETED are banned for Android 15+ targets.
                    FuelStatusWorker.startBackgroundMode(context)
                }
            }
        }
    }
}
