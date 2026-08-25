package com.angussoftware.fueldashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.angussoftware.fueldashboard.database.DatabaseDriverFactory
import com.angussoftware.fueldashboard.database.FuelSnapshotRepository
import com.angussoftware.fueldashboard.presentation.FuelProjection
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.presentation.ModelDrainRateDisplay
import com.angussoftware.fueldashboard.presentation.ProviderBurnRateDisplay
import com.angussoftware.fueldashboard.presentation.QuotaType
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not — toggle reflects reality */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wire fuel snapshot callbacks so the Android app shows fuel bars,
        // burn rates, and projections — same as desktop main.kt.
        wireFuelCallbacks()

        // Persistent status notification needs POST_NOTIFICATIONS on 13+.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val viewModel = remember { FuelViewModel.shared }
            val themeController = ThemeController

            DashboardTheme {
                FuelDashboardApp(
                    viewModel = viewModel,
                    themeController = themeController,
                )
            }
        }
    }

    private fun wireFuelCallbacks() {
        val viewModel = FuelViewModel.shared
        val driver = DatabaseDriverFactory().createDriver()
        val repo = FuelSnapshotRepository(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        viewModel.onLogFuelSnapshot = { tokensPct, _, activeAgentCount, activeModels, resetAt ->
            scope.launch {
                repo.insert(tokensPct, null, activeAgentCount, activeModels, resetAt)
            }
        }

        viewModel.onComputeBurnRate = {
            repo.computeBurnRate()
        }

        viewModel.onGetModelDrainRates = {
            repo.getModelDrainRates().map { rate ->
                ModelDrainRateDisplay(
                    model = rate.model,
                    totalFuelConsumed = rate.totalFuelConsumed,
                    sampleCount = rate.sampleCount,
                    avgDrainPerHr = rate.avgDrainPerHr,
                )
            }
        }

        viewModel.onGetFuelHistory = {
            repo.getRecent(120).mapNotNull { it.tokensPct }.reversed()
        }

        viewModel.onLogProviderSnapshots = { snapshots ->
            scope.launch {
                for (s in snapshots) {
                    repo.insertProviderSnapshot(
                        providerId = s.providerId,
                        providerName = s.providerName,
                        providerType = s.providerType,
                        remainingPct = s.remainingPct,
                        resetAt = s.resetAt,
                        windowHours = s.windowHours,
                    )
                }
            }
        }

        viewModel.onGetProviderBurnRates = {
            repo.getAllProviderBurnRates().map { br ->
                val report = viewModel.state.value.providerReports[br.providerId]
                val quotaType = report?.let { r ->
                    when (r.type) {
                        com.angussoftware.fueldashboard.model.ProviderType.WINDOW_CREDIT,
                        com.angussoftware.fueldashboard.model.ProviderType.RATE_LIMIT ->
                            QuotaType.RATE_WINDOW
                        com.angussoftware.fueldashboard.model.ProviderType.SPEND_BUDGET ->
                            if (r.creditsResetAt != null) QuotaType.CREDIT_POOL
                            else QuotaType.SPEND_ONLY
                    }
                } ?: QuotaType.RATE_WINDOW

                ProviderBurnRateDisplay(
                    providerId = br.providerId,
                    providerName = br.providerName,
                    currentPct = br.currentPct,
                    burnRatePerHr = br.burnRatePerHr,
                    hoursUntilReset = br.hoursUntilReset,
                    hoursUntilExhaustion = br.hoursUntilExhaustion,
                    projectedRemainingAtReset = br.projectedRemainingAtReset,
                    willMakeIt = br.willMakeIt,
                    history = br.history,
                    quotaType = quotaType,
                    windowHours = report?.windowHours ?: 0.0,
                )
            }
        }

        viewModel.onGetProjection = { currentPct, resetAt, burnRate ->
            repo.projectExhaustion(currentPct, resetAt, burnRate)?.let { proj ->
                val now = com.angussoftware.fueldashboard.util.epochMillis()
                val msUntilReset = resetAt?.let { it - now } ?: 3_600_000L * 5
                val hoursUntilReset = maxOf(0.0, msUntilReset / 3_600_000.0)
                FuelProjection(
                    currentPct = currentPct,
                    burnRatePerHr = if (burnRate > 0) burnRate else null,
                    hoursUntilReset = hoursUntilReset,
                    hoursUntilExhaustion = proj.hoursUntilExhaustion,
                    projectedRemainingAtReset = proj.projectedRemainingAtReset,
                    willMakeIt = proj.willMakeIt,
                    headroomPct = proj.projectedRemainingAtReset,
                    activeAgentCount = viewModel.state.value.acpAgents.count { it.status == "connected" },
                    activeModels = viewModel.state.value.acpAgents.mapNotNull { it.currentModel }.distinct().sorted(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure polling is active when the UI is visible — idempotent if
        // the notification service already started it. This covers the case
        // where the user stopped the notification (which stops polling) but
        // then opens the app: polling restarts with the UI.
        FuelViewModel.shared.startPolling()
    }
}
