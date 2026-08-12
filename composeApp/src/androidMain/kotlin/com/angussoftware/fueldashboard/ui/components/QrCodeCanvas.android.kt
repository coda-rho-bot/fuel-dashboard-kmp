package com.angussoftware.fueldashboard.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** Android stub — QR display not needed on mobile (mobile scans, doesn't display). */
actual fun renderQrBitmap(
    data: String,
    moduleSize: Int,
    margin: Int,
    informationDensity: Int,
): ImageBitmap? = null
