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
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not — toggle reflects reality */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    override fun onResume() {
        super.onResume()
        // Ensure polling is active when the UI is visible — idempotent if
        // the notification service already started it. This covers the case
        // where the user stopped the notification (which stops polling) but
        // then opens the app: polling restarts with the UI.
        FuelViewModel.shared.startPolling()
    }
}
