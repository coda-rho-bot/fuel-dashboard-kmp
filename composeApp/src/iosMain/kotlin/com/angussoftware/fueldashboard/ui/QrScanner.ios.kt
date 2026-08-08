package com.angussoftware.fueldashboard.ui

import androidx.compose.runtime.Composable

/** iOS has no QR scanner implemented yet — scanning is not available. */
actual val supportsQrScanning: Boolean = false

/**
 * iOS implementation of QR scanner — stub (not yet implemented).
 *
 * Desktop generates QR codes; iOS scanning would use AVFoundation.
 * For now this is a no-op.
 */
@Composable
actual fun rememberQrScanner(onResult: QrScanResult): QrScannerLauncher =
    QrScannerLauncher { /* No-op on iOS (not yet implemented) */ }
