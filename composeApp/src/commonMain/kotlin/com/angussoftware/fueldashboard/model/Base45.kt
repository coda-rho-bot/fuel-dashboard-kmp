package com.angussoftware.fueldashboard.model

/**
 * Base45 (RFC 9285) — byte encoding designed for QR codes: its 45-char
 * alphabet fits entirely inside QR alphanumeric mode (5.5 bits/char vs
 * byte mode's 8), shrinking QR modules ~23% vs base64.
 *
 * Encoding: byte pairs (big-endian u16) → 3 chars; a trailing odd byte
 * → 2 chars. Same scheme as EU COVID certificates (zlib + base45).
 */
object Base45 {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size * 3 / 2 + 3)
        var i = 0
        while (i + 1 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[(n / 45) % 45])
            sb.append(ALPHABET[(n / 45 / 45) % 45])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[(n / 45) % 45])
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null // invalid length
        val out = ArrayList<Byte>(text.length * 2 / 3 + 1)
        var i = 0
        while (i < text.length) {
            val rem = text.length - i
            if (rem >= 3) {
                val c0 = valueOf(text[i]) ?: return null
                val c1 = valueOf(text[i + 1]) ?: return null
                val c2 = valueOf(text[i + 2]) ?: return null
                val n = c0 + c1 * 45 + c2 * 45 * 45
                if (n > 0xFFFF) return null
                out.add((n shr 8).toByte())
                out.add((n and 0xFF).toByte())
                i += 3
            } else {
                val c0 = valueOf(text[i]) ?: return null
                val c1 = valueOf(text[i + 1]) ?: return null
                val n = c0 + c1 * 45
                if (n > 0xFF) return null
                out.add(n.toByte())
                i += 2
            }
        }
        return out.toByteArray()
    }

    private fun valueOf(c: Char): Int? {
        val idx = ALPHABET.indexOf(c)
        return if (idx >= 0) idx else null
    }
}
