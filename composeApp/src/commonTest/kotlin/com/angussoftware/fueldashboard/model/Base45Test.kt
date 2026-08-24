package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 9285 test vectors for [Base45] — the QR-payload codec. Correct but
 * previously untested; these vectors pin the wire format so any future
 * change that breaks QR interop fails here instead of on a phone camera.
 */
class Base45Test {

    // ── RFC 9285 §4 official vectors ─────────────────────────────────

    @Test
    fun rfcVector_ab() {
        assertEquals("BB8", Base45.encode("AB".encodeToByteArray()))
        assertContentEquals("AB".encodeToByteArray(), Base45.decode("BB8"))
    }

    @Test
    fun rfcVector_hello() {
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".encodeToByteArray()))
        assertContentEquals("Hello!!".encodeToByteArray(), Base45.decode("%69 VD92EX0"))
    }

    @Test
    fun vector_base45_handDerived() {
        // Hand-derived (pair arithmetic): "ba"=0x6261→UJC, "se"=0x7365→LQE,
        // "45"=0x3435→0R6. Kept as a full-payload round-trip pin.
        assertEquals("UJCLQE0R6", Base45.encode("base45".encodeToByteArray()))
        assertContentEquals("base45".encodeToByteArray(), Base45.decode("UJCLQE0R6"))
    }

    @Test
    fun rfcVector_empty() {
        assertEquals("", Base45.encode(ByteArray(0)))
        assertContentEquals(ByteArray(0), Base45.decode(""))
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun oddLengthEncodesAsTwoCharTail() {
        // One trailing byte → 2 chars (not padded to 3)
        val encoded = Base45.encode(byteArrayOf(0xFF.toByte()))
        assertEquals(2, encoded.length)
        assertContentEquals(byteArrayOf(0xFF.toByte()), Base45.decode(encoded))
    }

    @Test
    fun decodeRejectsInvalidLengthMod3Eq1() {
        // 4 chars: 3+1 — the trailing 1 is impossible in Base45
        assertNull(Base45.decode("BB8B"))
    }

    @Test
    fun decodeRejectsChunkOverflowBeyondUint16() {
        // "zzz" decodes to 44 + 44*45 + 44*45*45 = 91,124 > 0xFFFF
        assertNull(Base45.decode("zzz"))
    }

    @Test
    fun decodeRejectsTailOverflowBeyondUint8() {
        // 2-char tail max value is 44 + 44*45 = 2024 > 0xFF needs check
        // "zz" = 2024 → invalid for a single trailing byte
        assertNull(Base45.decode("zz"))
    }

    @Test
    fun decodeRejectsCharactersOutsideAlphabet() {
        assertNull(Base45.decode("BB~"))
    }

    @Test
    fun roundTrip_variousLengths() {
        for (len in 0..64) {
            val bytes = ByteArray(len) { (it * 31 + 7).toByte() }
            val encoded = Base45.encode(bytes)
            val decoded = Base45.decode(encoded)
            assertTrue(decoded != null, "round-trip failed at length $len")
            assertContentEquals(bytes, decoded!!, "length $len")
        }
    }

    @Test
    fun roundTrip_randomBytes() {
        repeat(50) { trial ->
            val bytes = ByteArray(trial * 3 + trial % 3) {
                kotlin.random.Random.nextInt(256).toByte()
            }
            val decoded = Base45.decode(Base45.encode(bytes))
            assertTrue(decoded != null, "trial $trial")
            assertContentEquals(bytes, decoded, "trial $trial")
        }
    }

    @Test
    fun alphabetFitsQrAlphanumericMode() {
        // The whole point of Base45: every char must be in the QR
        // alphanumeric charset (0-9, A-Z, space, $%*+-./:)
        val qrAlphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        for (c in Base45.encode(ByteArray(256) { it.toByte() })) {
            assertTrue(c in qrAlphanumeric, "char '$c' outside QR alphanumeric mode")
        }
    }
}
