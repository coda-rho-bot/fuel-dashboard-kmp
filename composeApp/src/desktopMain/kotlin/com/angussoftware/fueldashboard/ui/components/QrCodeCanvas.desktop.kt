package com.angussoftware.fueldashboard.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Image
import qrcode.QRCode
import qrcode.color.Colors

/**
 * Renders the QR code to raw PNG bytes using qrcode-kotlin's native renderer.
 *
 * [margin] is expressed in **modules** (QR "cells"), not pixels: the QR spec
 * mandates a quiet zone of at least 4 modules around the code, otherwise many
 * camera scanners refuse to lock onto it. We therefore multiply by [moduleSize]
 * so the baked-in quiet zone scales with the module size.
 *
 * Error correction defaults to [ErrorCorrectionLevel.LOW] in qrcode-kotlin,
 * which keeps the module count (QR version) as low as possible — fewer, larger
 * modules are far easier to scan off a screen than a dense high-version code.
 */
internal fun renderQrPngBytes(
    data: String,
    moduleSize: Int,
    margin: Int,
    informationDensity: Int,
): ByteArray? =
    runCatching {
        QRCode.ofSquares()
            .withSize(moduleSize)
            .withColor(Colors.BLACK)
            .withBackgroundColor(Colors.WHITE)
            .withMargin(margin * moduleSize)
            .withInformationDensity(informationDensity)
            .build(data)
            .render()
            .getBytes("PNG")
    }.getOrNull()

/**
 * Desktop (JVM) implementation: uses qrcode-kotlin's native renderer
 * to produce PNG bytes, then decodes to ImageBitmap via Skia.
 *
 * This avoids manual pixel conversion (byte ordering issues).
 */
actual fun renderQrBitmap(
    data: String,
    moduleSize: Int,
    margin: Int,
    informationDensity: Int,
): ImageBitmap? {
    val pngBytes = renderQrPngBytes(data, moduleSize, margin, informationDensity) ?: return null
    return runCatching {
        // Decode PNG to Skia Image → Bitmap → Compose ImageBitmap
        val skiaImage = Image.makeFromEncoded(pngBytes)
        val skiaBitmap = org.jetbrains.skia.Bitmap()
        skiaBitmap.allocPixels(skiaImage.imageInfo)
        skiaImage.readPixels(skiaBitmap)
        skiaBitmap.asComposeImageBitmap()
    }.getOrNull()
}
