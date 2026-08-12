package com.angussoftware.fueldashboard.model

// iOS stub — QR sync not implemented for iOS yet
actual fun compress(data: String): ByteArray = data.encodeToByteArray()
actual fun decompress(data: ByteArray): String = data.decodeToString()
