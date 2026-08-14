package com.angussoftware.fueldashboard.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZaiCreditCostTest {
    @Test
    fun computesCostWithOfficialMultipliers() {
        // GLM-4.7: input 4.6, output 16
        assertEquals(4.6 * 1000 + 16 * 200, zaiCreditCost("glm-4.7", 1000, 200))
        // GLM-5.3: input 6.9, output 24
        assertEquals(6.9 * 1000 + 24 * 200, zaiCreditCost("glm-5.3", 1000, 200))
    }

    @Test
    fun autoRoutedModelsShareTopTierMultipliers() {
        // GLM-5.2 and GLM-5.1 requests are routed to GLM-5.3 server-side
        assertEquals(
            zaiCreditCost("glm-5.3", 1000, 200),
            zaiCreditCost("glm-5.2", 1000, 200),
        )
        assertEquals(
            zaiCreditCost("glm-5.3", 1000, 200),
            zaiCreditCost("glm-5.1", 1000, 200),
        )
    }

    @Test
    fun normalizesModelNames() {
        assertEquals(zaiCreditCost("GLM-4.7", 10, 10), zaiCreditCost(" glm-4.7 ", 10, 10))
    }

    @Test
    fun unknownModelReturnsNull() {
        assertNull(zaiCreditCost("gpt-4o", 100, 100))
        assertFalse(ZaiCreditMultipliers.known("claude-3"))
        assertTrue(ZaiCreditMultipliers.known("glm-4.7"))
    }
}
