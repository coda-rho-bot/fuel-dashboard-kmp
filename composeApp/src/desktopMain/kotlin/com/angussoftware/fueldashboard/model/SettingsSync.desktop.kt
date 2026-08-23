package com.angussoftware.fueldashboard.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual fun compress(data: String): ByteArray {
    val bos = ByteArrayOutputStream()
    // Max compression — QR payload size is the scannability bound, so every
    // byte counts. (Java's GZIPOutputStream has no level constructor.)
    object : GZIPOutputStream(bos) {
        init { def.setLevel(Deflater.BEST_COMPRESSION) }
    }.use { it.write(data.encodeToByteArray()) }
    return bos.toByteArray()
}

actual fun decompress(data: ByteArray): String {
    val bis = ByteArrayInputStream(data)
    return GZIPInputStream(bis).use { it.readBytes().decodeToString() }
}
