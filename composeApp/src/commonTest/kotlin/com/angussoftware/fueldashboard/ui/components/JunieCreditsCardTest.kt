package com.angussoftware.fueldashboard.ui.components

import com.angussoftware.fueldashboard.network.JunieBalanceInfo
import com.angussoftware.fueldashboard.network.parseJunieBalance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JunieCreditsCardTest {
    @Test
    fun parseJunieBalanceExtractsBalanceAndLicense() {
        val info = parseJunieBalance(
            "✓ TASK RESULT  in 0:10 · cost \$0.21 · \$34.63 remaining\n" +
                "License: JetBrains Trial\nBalance left: \$34.38",
        )

        assertEquals(JunieBalanceInfo(balance = 34.38, license = "JetBrains Trial"), info)
    }

    @Test
    fun parseJunieBalanceTrimsLicenseAndRejectsMissingBalance() {
        val withWhitespace = parseJunieBalance("License:  JetBrains Pro  \nBalance left: \$12.00")

        assertEquals(JunieBalanceInfo(balance = 12.0, license = "JetBrains Pro"), withWhitespace)
        assertNull(parseJunieBalance("License: JetBrains Trial\nNo balance available"))
    }

    @Test
    fun formatJunieLastCheckedUsesRelativeTimeAndHandlesNever() {
        val now = 10_000_000L

        assertEquals("Never", formatJunieLastChecked(null, now))
        assertEquals("5m ago", formatJunieLastChecked(now - 5 * 60_000L, now))
        assertEquals("2h ago", formatJunieLastChecked(now - 2 * 60 * 60_000L, now))
    }
}