package com.angussoftware.fueldashboard

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import java.util.concurrent.TimeUnit

/**
 * Background-mode status updates after the dataSync foreground service has
 * stopped (Android 15+ enforces a 6-hour-per-24h runtime budget on dataSync
 * FGS; see FuelStatusService.onTimeout).
 *
 * A normal notification persists indefinitely without a foreground service —
 * this worker wakes every 15 minutes (WorkManager's minimum period, Doze
 * friendly), runs one poll cycle through the shared ViewModel, and re-renders
 * the notification. When the user next opens the app, MainActivity restarts
 * the foreground service (foreground starts are always allowed and reset the
 * 6-hour budget) and cancels this worker.
 */
class FuelStatusWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val vm = FuelViewModel.shared
            vm.pollOnce()
            FuelNotification.createChannel(applicationContext)
            FuelNotification.notify(
                applicationContext,
                FuelStatusModel.from(vm.state.value),
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the periodic schedule alive across transient failures
            // (no network in Doze, adapter errors). The notification simply
            // keeps its last content until the next successful cycle.
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "fuel_status_background"

        /** Schedule (or keep) the 15-minute background poll. Idempotent. */
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FuelStatusWorker>(15, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** Cancel the background poll (e.g. foreground service restarted). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Fallback entry point when the foreground service cannot start
         * (background start restrictions or exhausted dataSync budget on
         * Android 15+): schedule the periodic worker and run one cycle
         * immediately so the notification appears right away.
         */
        fun startBackgroundMode(context: Context) {
            ensureScheduled(context)
            val request = OneTimeWorkRequestBuilder<FuelStatusWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
