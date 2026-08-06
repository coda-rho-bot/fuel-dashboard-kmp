package com.angussoftware.fueldashboard.ui

import androidx.compose.runtime.Composable

/**
 * Desktop implementation of QR scanner — no-op.
 *
 * Desktop generates QR codes (for syncing to mobile), so scanning is not available.
 * The import button is hidden on desktop via platform checks in the UI.
 */
@Composable
actual fun rememberQrScanner(onResult: QrScanResult): QrScannerLauncher =
    QrScannerLauncher { /* No-op on desktop */ }
