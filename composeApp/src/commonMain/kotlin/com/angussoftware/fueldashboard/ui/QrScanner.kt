package com.angussoftware.fueldashboard.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific QR code scanner result callback type.
 */
typealias QrScanResult = (scannedText: String?) -> Unit

/**
 * Platform QR scanner entry point.
 *
 * On Android: launches the ZXing camera scanner activity.
 * On Desktop: no-op (desktop generates QR codes, doesn't scan them).
 *
 * Call [launchQrScanner] to start scanning. When the scan completes (or is cancelled),
 * [onResult] is called with the scanned text (or null if cancelled/failed).
 *
 * @param onResult Called with the scanned QR code text, or null if scanning was cancelled
 * @return A [QrScannerLauncher] that can be invoked to start scanning
 */
@Composable
expect fun rememberQrScanner(onResult: QrScanResult): QrScannerLauncher

/**
 * Launcher for the QR scanner.
 */
fun interface QrScannerLauncher {
    /**
     * Launches the QR scanner (camera preview).
     */
    fun launch()
}
