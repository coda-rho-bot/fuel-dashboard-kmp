package com.angussoftware.fueldashboard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.saveStringSetting
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
 * provider + credit pools. Tap → app. Action → stop (via FuelStatusStopReceiver).
 *
 * ## Android 15+ dataSync budget
 *
 * Android 15 (API 35) limits dataSync foreground services to 6 hours per
 * 24-hour period while the app is backgrounded. When the budget is spent the
 * system calls [onTimeout] and the service has a few seconds to call
 * stopSelf() or the system crashes the process. We stop gracefully and hand
 * background updates to [FuelStatusWorker] (15-min WorkManager polls updating
 * a normal notification — no foreground service needed to keep a
 * notification alive). Opening the app restarts this service, which both
 * restores real-time updates and resets the 6-hour budget.
 */
class FuelStatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FuelNotification.ACTION_STOP) {
            saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 13+: no POST_NOTIFICATIONS → the foreground service would
        // run with an invisible notification. Fail loudly-but-gracefully:
        // persist the disabled state and stop.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            saveStringSetting(FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false")
            stopSelf()
            return START_NOT_STICKY
        }

        FuelNotification.createChannel(this)

        // Enter foreground immediately with a placeholder; real data replaces
        // it as soon as the state flow emits. Wrapped: a system START_STICKY
        // restart with an exhausted dataSync budget (Android 15+) can also
        // hit the background-start restriction — fall back to worker mode
        // and do not stick, or the platform would restart-loop the crash.
        try {
            startAsForeground(FuelNotification.build(this, null))
        } catch (e: IllegalStateException) {
            FuelStatusWorker.startBackgroundMode(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // FGS mode and worker mode are exclusive — any pending background
        // poll (scheduled before promotion, or left over from the app-open
        // race where isRunning was still false) dies the moment the service
        // actually enters the foreground. Doing this here covers every
        // promotion path, not just MainActivity.onStart.
        FuelStatusWorker.cancel(this)

        // Collect exactly once — onStartCommand can fire repeatedly (e.g.
        // START_STICKY restarts, repeated startService calls) and each
        // unguarded launch added a duplicate state collector.
        if (!::collectorJob.isInitialized || !collectorJob.isActive) {
            collectorJob = scope.launch {
                FuelViewModel.shared.let { vm ->
                    vm.startPolling()
                    vm.state.collect { state ->
                        FuelNotification.notify(
                            this@FuelStatusService,
                            com.angussoftware.fueldashboard.model.FuelStatusModel.from(state),
                        )
                    }
                }
            }
        }
        return START_STICKY
    }

    private lateinit var collectorJob: kotlinx.coroutines.Job

    /**
     * Android 15+ (API 35): the dataSync runtime budget is exhausted. The
     * system gives us a few seconds to stop; failing to do so crashes the
     * process with RemoteServiceException ("A foreground service of type
     * dataSync did not stop within its timeout"). Stop gracefully and hand
     * off to WorkManager so the notification keeps updating in the
     * background at reduced cadence.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FuelViewModel.shared.stopPolling()
        // DETACH (not REMOVE): the notification hands off seamlessly — the
        // worker re-posts the same ID on its first run, so there is no
        // flicker where the status vanishes and reappears.
        stopForeground(STOP_FOREGROUND_DETACH)
        // Budget is exhausted for this 24h window — a dataSync FGS start
        // would throw ForegroundServiceStartNotAllowedException until the
        // user opens the app (which resets the timer). Worker takes over.
        FuelStatusWorker.startBackgroundMode(this)
        stopSelf()
    }

    override fun onDestroy() {
        // Stop the shared ViewModel's polling loop — scope.cancel() only
        // cancels the service's state-collector coroutine, not the polling
        // job which lives on the ViewModel's own scope. Without this, the
        // 30s poll loop + HTTP adapters keep running invisibly after the
        // notification is dismissed.
        FuelViewModel.shared.stopPolling()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FuelNotification.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FuelNotification.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** True while the foreground service is alive (same process only). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start the persistent notification (idempotent). If the platform
         * refuses a background foreground-service start (Android 12+ bg
         * restrictions, or exhausted dataSync budget on 15+), fall back to
         * WorkManager background mode instead of crashing.
         *
         * Catches IllegalStateException (the common ancestor of
         * ServiceStartNotAllowedException / ForegroundServiceStartNotAllowed-
         * Exception, API 31+) so the catch resolves on all supported API
         * levels — those exceptions can only be thrown on 31+, where the
         * ancestor type is guaranteed present.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, FuelStatusService::class.java))
            } catch (e: IllegalStateException) {
                FuelStatusWorker.startBackgroundMode(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FuelStatusService::class.java))
            FuelStatusWorker.cancel(context)
        }

        fun isEnabled(): Boolean =
            com.angussoftware.fueldashboard.settings.loadStringSetting(
                FuelSettingsKeys.STATUS_NOTIFICATION_ENABLED, "false",
            ).toBoolean()
    }
}
