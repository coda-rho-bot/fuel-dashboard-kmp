package com.angussoftware.fueldashboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Android supports QR scanning via ZXing camera scanner. */
actual val supportsQrScanning: Boolean = true

/**
 * Android implementation of QR scanner using ZXing Android Embedded.
 *
 * Launches a full-screen camera scanner activity. When a QR code is detected
 * (or the user cancels), [onResult] is called with the decoded text or null.
 */
@Composable
actual fun rememberQrScanner(onResult: QrScanResult): QrScannerLauncher {
    val launcher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            onResult(result.contents)
        },
    )

    return QrScannerLauncher {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point camera at the QR code")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        launcher.launch(options)
    }
}
