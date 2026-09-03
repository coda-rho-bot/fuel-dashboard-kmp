package com.angussoftware.fueldashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Shared notification rendering for the persistent status surface.
 *
 * Used by [FuelStatusService] (foreground mode, real-time updates) and
 * [FuelStatusWorker] (background mode after the FGS dataSync budget is
 * exhausted — a normal notification persists without a foreground service).
 *
 * The stop action targets [FuelStatusReceiver] in both modes so stopping
 * works identically whether the service or the worker owns the notification.
 */
object FuelNotification {

    const val CHANNEL_ID = "fuel_status"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP = "com.angussoftware.fueldashboard.STOP_STATUS"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.fuel_status),
                NotificationManager.IMPORTANCE_LOW, // silent, no badge spam
            ).apply {
                description = "Persistent quota and credit status"
                setShowBadge(false)
            },
        )
    }

    fun showIcon(context: Context): Boolean =
        loadStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_SHOW_ICON, "true").toBoolean()

    fun build(context: Context, model: FuelStatusModel?): Notification {
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, FuelStatusStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Fixed app title; data lives in the body / expanded HUD — the
        // headline provider no longer masquerades as the notification title.
        val title = context.getString(R.string.fuel_status)
        val body: CharSequence = model?.collapsedBodyText(context.getString(R.string.loading_status))
            ?: context.getString(R.string.loading_status)

        // Choose small icon: fuel drop or transparent (hide from status bar)
        // while keeping the notification at IMPORTANCE_LOW (not minimized).
        val smallIcon = if (showIcon(context)) {
            R.drawable.ic_stat_fuel
        } else {
            R.drawable.ic_status_transparent
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launch)
            .addAction(0, context.getString(R.string.stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Expanded view: mini HUD — one progress-bar row per provider,
        // credit totals beneath. Falls back to plain big text if the model
        // is empty.
        if (model != null && model.hasAnyData) {
            val expanded = android.widget.RemoteViews(context.packageName, R.layout.notification_status_expanded)
            expanded.removeAllViews(R.id.provider_rows)
            for (line in model.quotaLines) {
                val row = android.widget.RemoteViews(context.packageName, R.layout.notification_provider_row)
                row.setTextViewText(R.id.provider_name, line.name)
                val cd = FuelStatusModel.formatCountdown(line.resetsAt)
                row.setTextViewText(
                    R.id.provider_detail,
                    if (cd != null) "${line.remainingPct ?: 0}% · $cd" else "${line.remainingPct ?: 0}%",
                )
                // Quota remaining gauge
                row.setProgressBar(R.id.provider_progress, 100, line.remainingPct ?: 0, false)
                // Time remaining gauge (window countdown)
                val timePct = line.timeRemainingPct()
                row.setProgressBar(R.id.timer_progress, 100, timePct ?: 0, false)
                // Show/hide timer bar — hide if no window data
                row.setViewVisibility(
                    R.id.timer_progress,
                    if (timePct != null) android.view.View.VISIBLE else android.view.View.GONE,
                )
                expanded.addView(R.id.provider_rows, row)
            }
            val creditsText = model.creditLines.mapNotNull { c ->
                when {
                    c.creditsTotal != null -> "${c.name}: ${c.creditsTotal} cr"
                    c.junieBalance != null -> "${c.name}: $${formatRoot("%.2f", c.junieBalance)}"
                    else -> null
                }
            }.joinToString("  ·  ")
            expanded.setTextViewText(R.id.credits_text, creditsText)
            val mins = (System.currentTimeMillis() - model.lastUpdated) / 60_000
            expanded.setTextViewText(
                R.id.updated_text,
                if (model.lastUpdated > 0) context.getString(R.string.updated_ago, mins) else "",
            )
            builder.setCustomBigContentView(expanded)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        return builder.build()
    }

    /**
     * Post (or update) the status notification. Callers must have created
     * the channel once via [createChannel] (idempotent, but a binder call —
     * don't do it per update).
     */
    fun notify(context: Context, model: FuelStatusModel?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, build(context, model))
    }
}
