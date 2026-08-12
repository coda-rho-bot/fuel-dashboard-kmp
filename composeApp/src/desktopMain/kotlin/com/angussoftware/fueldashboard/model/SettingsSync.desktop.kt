package com.angussoftware.fueldashboard.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual fun compress(data: String): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(data.encodeToByteArray()) }
    return bos.toByteArray()
}

actual fun decompress(data: ByteArray): String {
    val bis = ByteArrayInputStream(data)
    return GZIPInputStream(bis).use { it.readBytes().decodeToString() }
}
