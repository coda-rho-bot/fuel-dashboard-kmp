package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * Renders a QR code from a string.
 *
 * Uses platform-specific rendering (see [renderQrBitmap] actual implementations).
 *
 * @param data The string to encode in the QR code
 * @param modifier Modifier for sizing
 * @param moduleSize Pixel size per module (platform-specific default)
 * @param margin Quiet-zone modules around the code
 */
@Composable
fun QrCodeCanvas(
    data: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    moduleSize: Int = 20,
    margin: Int = 4,
) {
    val bitmap = remember(data, moduleSize, margin) {
        renderQrBitmap(data, moduleSize, margin, minimumInformationDensity(data))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(8.dp),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "QR code for settings sync",
                contentScale = ContentScale.FillWidth,
                // Nearest-neighbour sampling: the QR bitmap is rendered at a much
                // higher resolution than it is displayed, so the default (linear)
                // filtering blurs the sharp module edges into grey and makes the
                // code unscannable. FilterQuality.None keeps every module crisp.
                filterQuality = FilterQuality.None,
            )
        } ?: Box(
            modifier = Modifier.fillMaxWidth().height(200.dp)
                .background(Color.White),
        )
    }
}

/**
 * Platform-specific QR code rendering. Returns an ImageBitmap or null on failure.
 *
 * @param informationDensity the QR "version" (1..40) to encode at. Callers should
 * pass [minimumInformationDensity] so the code stays as low-density (easy to scan)
 * as possible.
 */
expect fun renderQrBitmap(
    data: String,
    moduleSize: Int,
    margin: Int,
    informationDensity: Int,
): ImageBitmap?

/**
 * Finds the smallest QR "version" (information density, 1..40) that can hold [data]
 * at the given [errorCorrectionLevel].
 *
 * qrcode-kotlin's own [QRCodeProcessor.infoDensityForDataAndECL] only searches up to
 * [ErrorCorrectionLevel.maxTypeNum] (e.g. version 20 for LOW) and otherwise jumps
 * straight to the maximum, version 40. That produces a needlessly dense 177x177
 * module code for payloads only slightly above the version-20 limit, which is
 * effectively unscannable off a screen. Searching the full range keeps the code as
 * small as the data actually requires.
 */
fun minimumInformationDensity(
    data: String,
    errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.LOW,
): Int {
    val processor = QRCodeProcessor(data, errorCorrectionLevel)
    for (version in 1..QRCodeProcessor.MAXIMUM_INFO_DENSITY) {
        if (runCatching { processor.encode(version) }.isSuccess) return version
    }
    return QRCodeProcessor.MAXIMUM_INFO_DENSITY
}

/**
 * Checks if the given data can fit in a QR code at LOW error correction, and reports
 * the smallest QR version that would be used.
 *
 * `tooLarge` gates on the reliably-scannable bound (version ≤ 20 — matches the
 * dialog's guidance text), not the theoretical version-40 byte maximum: a
 * version 21-40 code renders but is effectively unscannable on phones.
 */
fun estimateQrCapacity(data: String): QrCapacityResult {
    val byteLength = data.toByteArray(Charsets.UTF_8).size
    // ~667 bytes at LOW error correction for version 20 (reliable bound)
    val maxBytes = 667
    val processor = QRCodeProcessor(data, ErrorCorrectionLevel.LOW)
    val version = (1..QRCodeProcessor.MAXIMUM_INFO_DENSITY).firstOrNull { v ->
        runCatching { processor.encode(v) }.isSuccess
    }
    return if (version == null || version > RELIABLE_MAX_VERSION) {
        QrCapacityResult(tooLarge = true, byteLength = byteLength, maxBytes = maxBytes)
    } else {
        QrCapacityResult(tooLarge = false, byteLength = byteLength, version = version)
    }
}

/** Highest QR version considered reliably scannable by phone cameras. */
const val RELIABLE_MAX_VERSION = 20

data class QrCapacityResult(
    val tooLarge: Boolean,
    val byteLength: Int,
    val version: Int = 0,
    val maxBytes: Int = 0,
)
