package com.angussoftware.fueldashboard.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZaiCreditCostTest {
    @Test
    fun computesCostWithOfficialFormula() {
        // Official formula (docs.z.ai/devpack/teamplan "Credit Calculation"):
        //   credits = (input×inMult + cachedIn×cachedMult + output×outMult) / 10,000
        // Expected values derived INDEPENDENTLY from the formula, not from
        // the implementation:
        //   GLM-4.7 (4.6 in / 16 out): (1000×4.6 + 200×16) / 10,000 = 0.78
        //   GLM-5.3 (6.9 in / 24 out): (1000×6.9 + 200×24) / 10,000 = 1.17
        assertEquals(0.78, zaiCreditCost("glm-4.7", 1000, 200)!!, 1e-9)
        assertEquals(1.17, zaiCreditCost("glm-5.3", 1000, 200)!!, 1e-9)
    }

    @Test
    fun planScaleSanityCheck() {
        // A Team Standard Seat allows 15,000 credits per 5-hour window. A
        // heavy but realistic 5h session (say 2M input + 400K output tokens
        // on GLM-4.7) must cost a small fraction of that — not multiples of
        // the entire window budget. This anchors the /10,000 divisor to
        // real-world plan economics; the old code (no divisor) returned
        // 16.8 MILLION credits for this session.
        val credits = zaiCreditCost("glm-4.7", 2_000_000, 400_000)!!
        assertTrue(credits in 1.0..5_000.0, "expected a small fraction of the 15,000-credit window, got $credits")
        // Exact: (2M×4.6 + 400K×16) / 10,000 = 1,560
        assertEquals(1_560.0, credits, 1e-6)
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
