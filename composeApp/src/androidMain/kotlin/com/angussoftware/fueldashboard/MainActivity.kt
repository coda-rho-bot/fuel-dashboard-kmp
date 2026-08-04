package com.angussoftware.fueldashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel = remember { FuelViewModel() }
            val themeController = ThemeController

            DashboardTheme {
                FuelDashboardApp(
                    viewModel = viewModel,
                    themeController = themeController,
                )
            }
        }
    }
}
