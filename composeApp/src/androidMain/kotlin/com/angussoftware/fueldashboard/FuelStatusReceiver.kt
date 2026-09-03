package com.angussoftware.fueldashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.saveStringSetting

/**
 * Stop action for the persistent notification (both service and worker
 * modes): disables the setting, stops the foreground service if running,
 * cancels the background worker, and removes the notification.
 *
 * Delivered only via explicit-component PendingIntents created by this app
 * (see FuelNotification.build) — no intent-filter, exported=false, so no
 * other app can trigger it.
 */
class FuelStatusStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false")
        context.stopService(Intent(context, FuelStatusService::class.java))
        FuelStatusWorker.cancel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(FuelNotification.NOTIFICATION_ID)
    }
}

/**
 * Device-boot restore. Apps targeting Android 15+ may NOT start a dataSync
 * foreground service from BOOT_COMPLETED, so restore runs in worker mode —
 * the periodic worker wakes within ~15 minutes and re-posts the
 * notification. When the user opens the app, MainActivity promotes back to
 * the foreground service.
 *
 * Exported because the system must deliver BOOT_COMPLETED; the action is a
 * protected broadcast, so only the system can send it.
 */
class FuelStatusBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && FuelStatusService.isEnabled()) {
            FuelStatusWorker.startBackgroundMode(context)
        }
    }
}
