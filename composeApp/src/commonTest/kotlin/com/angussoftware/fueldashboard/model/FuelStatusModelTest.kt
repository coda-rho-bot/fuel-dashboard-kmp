package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.presentation.DashboardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelStatusModelTest {

    private fun report(
        id: String,
        name: String,
        remainingPct: Int? = null,
        resetsAt: Long? = null,
        creditsTotal: Int? = null,
    ) = ProviderReport(
        providerId = id,
        displayName = name,
        type = if (creditsTotal != null) ProviderType.SPEND_BUDGET else ProviderType.WINDOW_CREDIT,
        remainingPct = remainingPct,
        resetsAt = resetsAt,
        creditsTotal = creditsTotal,
    )

    @Test
    fun headlineIsTheLowestRemainingProvider() {
        val state = DashboardState(
            providerReports = mapOf(
                "a" to report("a", "z.ai", remainingPct = 60, resetsAt = 1000L),
                "b" to report("b", "Letta", remainingPct = 30, resetsAt = 2000L),
            ),
        )
        val model = FuelStatusModel.from(state)

        assertNotNull(model.headline)
        assertEquals("Letta", model.headline!!.name)
        assertEquals(30, model.headline!!.remainingPct)
        assertEquals(2000L, model.headline!!.resetsAt)
    }

    @Test
    fun creditOnlyProvidersNeverHeadlineButAppearAsCredits() {
        val state = DashboardState(
            providerReports = mapOf(
                "letta" to report("letta", "Letta Cloud", creditsTotal = 1234, remainingPct = null),
            ),
            junieBalance = 38.50,
        )
        val model = FuelStatusModel.from(state)

        assertNull(model.headline, "credit-only provider must not headline")
        // Letta credits + Junie balance = two credit lines
        assertEquals(2, model.creditLines.size)
        assertEquals(1234, model.creditLines[0].creditsTotal)
        assertEquals("Junie", model.creditLines[1].name)
        assertTrue(model.hasAnyData)
    }

    @Test
    fun junieBalanceAppearsAsCreditLine() {
        val state = DashboardState(junieBalance = 12.75)
        val model = FuelStatusModel.from(state)

        assertEquals(1, model.creditLines.size)
        assertEquals("Junie", model.creditLines[0].name)
        assertEquals(12.75, model.creditLines[0].junieBalance)
    }

    @Test
    fun emptyStateHasNoData() {
        val model = FuelStatusModel.from(DashboardState())
        assertNull(model.headline)
        assertTrue(!model.hasAnyData)
        assertTrue(model.quotaLines.isEmpty())
    }

    @Test
    fun countdownFormatting() {
        val now = 1_000_000L
        assertEquals("45m", FuelStatusModel.formatCountdown(now + 45 * 60_000, now))
        assertEquals("2h 15m", FuelStatusModel.formatCountdown(now + (2 * 60 + 15) * 60_000, now))
        assertEquals("3d 4h", FuelStatusModel.formatCountdown(now + ((3 * 24 + 4) * 60) * 60_000, now))
        assertEquals("resetting", FuelStatusModel.formatCountdown(now - 1, now))
        assertNull(FuelStatusModel.formatCountdown(null, now))
    }

    @Test
    fun unavailableProvidersAreExcluded() {
        val state = DashboardState(
            providerReports = mapOf(
                "dead" to report("dead", "Dead Provider", remainingPct = 5).copy(available = false),
                "alive" to report("alive", "Alive", remainingPct = 80),
            ),
        )
        val model = FuelStatusModel.from(state)

        assertEquals(1, model.quotaLines.size)
        assertEquals("Alive", model.quotaLines[0].name)
        assertEquals("Alive", model.headline!!.name)
    }
}
