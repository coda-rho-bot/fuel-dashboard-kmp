package com.angussoftware.fueldashboard

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme

fun main() = application {
    val viewModel = FuelViewModel()

    val windowState = rememberWindowState(
        width = 480.dp,
        height = 720.dp,
    )

    Window(
        onCloseRequest = {
            viewModel.close()
            exitApplication()
        },
        title = "Fuel Dashboard",
        state = windowState,
    ) {
        DashboardTheme {
            FuelDashboardApp(viewModel = viewModel)
        }
    }
}
