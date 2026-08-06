package com.angussoftware.fueldashboard.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific QR code scanner result callback type.
 */
typealias QrScanResult = (scannedText: String?) -> Unit

/**
 * Whether this platform supports QR code scanning via camera.
 *
 * - Android: true (ZXing camera scanner)
 * - Desktop / iOS: false (no camera scanner implemented)
 */
expect val supportsQrScanning: Boolean

/**
 * Platform QR scanner entry point.
 *
 * On Android: launches the ZXing camera scanner activity.
 * On Desktop/iOS: no-op (these platforms can still export QR codes and use text codes).
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
