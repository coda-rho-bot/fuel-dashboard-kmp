package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * Renders a QR code from a string using [QRCodeProcessor] and Compose [Canvas].
 *
 * Uses LOW error correction to maximize data capacity. Each module is drawn as a
 * filled rectangle — black for dark modules, white for light modules. The QR code
 * includes a 4-module quiet-zone border for scannability.
 *
 * @param data The string to encode in the QR code
 * @param size Display size of the QR code (square)
 * @param darkColor Color for dark modules (default black)
 * @param lightColor Color for light modules (default white)
 */
@Composable
fun QrCodeCanvas(
    data: String,
    size: Dp = 240.dp,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White,
) {
    // Encode QR data once and cache
    val modules = remember(data) {
        runCatching {
            QRCodeProcessor(
                data = data,
                errorCorrectionLevel = ErrorCorrectionLevel.LOW,
            ).encode()
        }.getOrNull()
    }

    Canvas(modifier = Modifier.size(size)) {
        val canvasSize = this.size.minDimension

        if (modules == null) {
            // Error state — draw white background with a red X
            drawRect(lightColor)
            val center = canvasSize / 2f
            drawLine(
                color = Color.Red,
                start = Offset(center - 40f, center - 40f),
                end = Offset(center + 40f, center + 40f),
                strokeWidth = 4f,
            )
            drawLine(
                color = Color.Red,
                start = Offset(center + 40f, center - 40f),
                end = Offset(center - 40f, center + 40f),
                strokeWidth = 4f,
            )
            return@Canvas
        }

        val moduleCount = modules.size
        // Include a 4-module quiet zone on each side (QR spec standard)
        val quietZone = 4
        val totalModules = moduleCount + quietZone * 2
        val pixelPerModule = canvasSize / totalModules

        // Draw background (includes quiet zone)
        drawRect(color = lightColor)

        // Draw dark modules only (light modules are already white from background)
        for (row in 0 until moduleCount) {
            for (col in 0 until moduleCount) {
                if (modules[row][col].dark) {
                    drawRect(
                        color = darkColor,
                        topLeft = Offset(
                            x = (col + quietZone) * pixelPerModule,
                            y = (row + quietZone) * pixelPerModule,
                        ),
                        size = Size(pixelPerModule, pixelPerModule),
                    )
                }
            }
        }
    }
}

/**
 * Checks if the given data can fit in a QR code at LOW error correction.
 * Maximum capacity at version 40, LOW ECL is ~2,953 bytes.
 */
fun estimateQrCapacity(data: String): QrCapacityResult {
    val byteLength = data.toByteArray(Charsets.UTF_8).size
    return runCatching {
        val density = QRCodeProcessor.infoDensityForDataAndECL(
            data,
            ErrorCorrectionLevel.LOW,
        )
        if (density >= 40) {
            // At version 40, check if it actually fits
            val maxBytes = 2953 // Approximate max byte capacity at LOW ECL
            if (byteLength > maxBytes) {
                QrCapacityResult(tooLarge = true, byteLength = byteLength, maxBytes = maxBytes)
            } else {
                QrCapacityResult(tooLarge = false, byteLength = byteLength, version = 40)
            }
        } else {
            QrCapacityResult(tooLarge = false, byteLength = byteLength, version = density)
        }
    }.getOrElse {
        QrCapacityResult(tooLarge = true, byteLength = byteLength, maxBytes = 2953)
    }
}

data class QrCapacityResult(
    val tooLarge: Boolean,
    val byteLength: Int,
    val version: Int = 0,
    val maxBytes: Int = 0,
)
