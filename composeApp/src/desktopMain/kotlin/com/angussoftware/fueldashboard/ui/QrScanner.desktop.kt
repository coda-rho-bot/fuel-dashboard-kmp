package com.angussoftware.fueldashboard.ui

import androidx.compose.runtime.Composable

/** Desktop has no camera scanner — QR scanning is not available. */
actual val supportsQrScanning: Boolean = false

/**
 * Desktop implementation of QR scanner — no-op.
 *
 * Desktop generates QR codes (for syncing to mobile), so scanning is not available.
 * The Import button still renders on desktop; only the camera-scan option inside
 * the import dialog is hidden (supportsQrScanning = false) — text-code import works.
 */
@Composable
actual fun rememberQrScanner(onResult: QrScanResult): QrScannerLauncher =
    QrScannerLauncher { /* No-op on desktop */ }
