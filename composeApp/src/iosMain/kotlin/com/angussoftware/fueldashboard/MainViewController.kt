package com.angussoftware.fueldashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS entry point — returns the root Compose view controller.
 * Full iOS UI implementation is deferred (stub for MVP).
 */
@Suppress("unused")
fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Fuel Dashboard — iOS coming soon")
        }
    }
}
