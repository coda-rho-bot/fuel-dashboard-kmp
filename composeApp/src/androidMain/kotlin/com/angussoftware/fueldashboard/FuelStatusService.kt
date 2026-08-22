package com.angussoftware.fueldashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service: persistent, expandable notification with quota
 * remaining %, time-to-reset, and credit totals.
 *
 * Shares the process-wide [FuelViewModel.shared] instance with the Activity —
 * one polling loop, one adapter set. The notification re-renders from the
 * same DashboardState the UI shows, so numbers always match.
 *
 * Collapsed: headline (most critical provider). Expanded: one line per
 * provider + credit pools. Tap → app. Action → stop service.
 */
class FuelStatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()

        // Enter foreground immediately with a placeholder; real data replaces
        // it as soon as the state flow emits.
        startAsForeground(buildNotification(null))

        scope.launch {
            FuelViewModel.shared.let { vm ->
                vm.startPolling()
                vm.state.collect { state ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(FuelStatusModel.from(state)))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Fuel status",
                NotificationManager.IMPORTANCE_LOW, // silent, no badge spam
            ).apply {
                description = "Persistent quota and credit status"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(model: FuelStatusModel?): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FuelStatusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Fixed app title; data lives in the body / expanded HUD — the
        // headline provider no longer masquerades as the notification title.
        val title = getString(R.string.fuel_status)
        val body: CharSequence = when {
            model == null || !model.hasAnyData -> getString(R.string.loading_status)
            else -> buildString {
                // Show ALL quota providers in collapsed text (not just headline)
                for ((i, line) in model.quotaLines.withIndex()) {
                    if (i > 0) append("  ·  ")
                    val pct = line.remainingPct?.let { "$it%" } ?: "—"
                    val cd = FuelStatusModel.formatCountdown(line.resetsAt)
                    append(if (cd != null) "${line.name} $pct · $cd" else "${line.name} $pct")
                }
                model.creditLines.firstOrNull { it.creditsTotal != null }?.let {
                    append("  ·  ${it.name} ${it.creditsTotal} cr")
                }
                model.creditLines.firstOrNull { it.junieBalance != null }?.let {
                    append("  ·  ${it.name} $${"%.2f".format(it.junieBalance!!)}")
                }
            }.ifEmpty { getString(R.string.loading_status) }
        }

        // Choose small icon: real icon or transparent (hide from status bar)
        // while keeping the notification at IMPORTANCE_LOW (not minimized).
        val showIcon = loadStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_SHOW_ICON, "true").toBoolean()
        val smallIcon = if (showIcon) {
            android.R.drawable.stat_sys_download_done // placeholder until app icon set
        } else {
            R.drawable.ic_status_transparent
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launch)
            .addAction(0, getString(R.string.stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Expanded view: mini HUD — one progress-bar row per provider,
        // credit totals beneath. Falls back to plain big text if the model
        // is empty.
        if (model != null && model.hasAnyData) {
            val expanded = android.widget.RemoteViews(packageName, R.layout.notification_status_expanded)
            expanded.removeAllViews(R.id.provider_rows)
            for (line in model.quotaLines) {
                val row = android.widget.RemoteViews(packageName, R.layout.notification_provider_row)
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
                    c.junieBalance != null -> "${c.name}: $${"%.2f".format(c.junieBalance)}"
                    else -> null
                }
            }.joinToString("  ·  ")
            expanded.setTextViewText(R.id.credits_text, creditsText)
            val mins = (System.currentTimeMillis() - model.lastUpdated) / 60_000
            expanded.setTextViewText(
                R.id.updated_text,
                if (model.lastUpdated > 0) getString(R.string.updated_ago, mins) else "",
            )
            builder.setCustomBigContentView(expanded)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "fuel_status"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.angussoftware.fueldashboard.STOP_STATUS"

        /** Start the persistent notification (idempotent). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, FuelStatusService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FuelStatusService::class.java))
        }

        fun isEnabled(): Boolean =
            loadStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false").toBoolean()
    }
}
