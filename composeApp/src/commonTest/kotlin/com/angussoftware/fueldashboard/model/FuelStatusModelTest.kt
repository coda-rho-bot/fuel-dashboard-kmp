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
    fun creditOnlyProvidersAreExcludedFromQuotaLines() {
        val state = DashboardState(
            providerReports = mapOf(
                "zai" to report("zai", "z.ai", remainingPct = 55, resetsAt = 9000L),
                "letta" to report("letta", "Letta Cloud", creditsTotal = 1234, remainingPct = null),
            ),
        )
        val model = FuelStatusModel.from(state)

        // Credit-only provider appears ONLY in creditLines — no "—" quota row.
        assertEquals(1, model.quotaLines.size)
        assertEquals("z.ai", model.quotaLines[0].name)
        assertEquals(1, model.creditLines.size)
        assertEquals("Letta Cloud", model.creditLines[0].name)
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

    @Test
    fun quotaLinesFollowUserProviderOrderNotAlphabetical() {
        // Settings order: zeta first, alpha second, beta third — deliberately
        // NOT alphabetical. Reports must render in this exact order.
        val settings = MultiProviderSettings(
            providers = listOf(
                ProviderConfig(id = "zeta", kind = ProviderKind.ZAI, apiKey = "k"),
                ProviderConfig(id = "alpha", kind = ProviderKind.LETTA_CLOUD, apiKey = "k"),
                ProviderConfig(id = "beta", kind = ProviderKind.OPENAI, apiKey = "k"),
            ),
        )
        val state = DashboardState(
            settings = settings,
            providerReports = mapOf(
                "beta" to report("beta", "Beta", remainingPct = 50),
                "alpha" to report("alpha", "Alpha", remainingPct = 60),
                "zeta" to report("zeta", "Zeta", remainingPct = 40),
            ),
        )
        val model = FuelStatusModel.from(state)

        assertEquals(listOf("Zeta", "Alpha", "Beta"), model.quotaLines.map { it.name })
    }

    @Test
    fun unknownProviderReportsFallBackAlphabeticallyAfterKnownOnes() {
        // "orphan" is not in settings; "zeta" is. Known order first, then
        // unknown reports alphabetically.
        val settings = MultiProviderSettings(
            providers = listOf(
                ProviderConfig(id = "zeta", kind = ProviderKind.ZAI, apiKey = "k"),
            ),
        )
        val state = DashboardState(
            settings = settings,
            providerReports = mapOf(
                "zeta" to report("zeta", "Zeta", remainingPct = 40),
                "orphan" to report("orphan", "Orphan", remainingPct = 10),
            ),
        )
        val model = FuelStatusModel.from(state)

        assertEquals("Zeta", model.quotaLines[0].name)
        assertEquals("Orphan", model.quotaLines[1].name)
    }

    // ── collapsedBodyText (status-surface renderer) ─────────────────

    private fun modelWith(
        quota: List<FuelStatusModel.QuotaLine> = emptyList(),
        credits: List<FuelStatusModel.CreditLine> = emptyList(),
        headline: FuelStatusModel.Headline? = null,
    ) = FuelStatusModel(
        headline = headline,
        quotaLines = quota,
        creditLines = credits,
        lastUpdated = 0L,
    )

    @Test
    fun collapsedBody_noDataShowsLoadingText() {
        assertEquals("Loading…", modelWith().collapsedBodyText("Loading…"))
    }

    @Test
    fun collapsedBody_joinsQuotaLinesWithSeparator() {
        val m = modelWith(
            quota = listOf(
                FuelStatusModel.QuotaLine("z.ai", 58, resetsAt = null, available = true),
                FuelStatusModel.QuotaLine("Letta", 75, resetsAt = null, available = true),
            ),
            headline = FuelStatusModel.Headline("z.ai", 58, null),
        )
        val body = m.collapsedBodyText("Loading…")
        assertTrue(body.startsWith("z.ai 58%"), body)
        assertTrue(body.contains("·  Letta 75%"), body)
    }

    @Test
    fun collapsedBody_nullPctRendersDash_notZero() {
        val m = modelWith(
            quota = listOf(FuelStatusModel.QuotaLine("Unknown provider", null, resetsAt = null, available = true)),
            headline = FuelStatusModel.Headline("Unknown provider", null, null),
        )
        // hasAnyData requires headline != null; null pct renders as "—", never "0%"
        assertEquals("Unknown provider —", m.collapsedBodyText("Loading…"))
    }

    @Test
    fun collapsedBody_includesCreditTotalAndJunieBalance() {
        val m = modelWith(
            credits = listOf(
                FuelStatusModel.CreditLine("Letta Cloud", creditsTotal = 1200, creditsUsed = 600),
                FuelStatusModel.CreditLine("Junie", creditsTotal = null, creditsUsed = null, junieBalance = 38.5),
            ),
        )
        val body = m.collapsedBodyText("Loading…")
        assertTrue(body.contains("Letta Cloud 1200 cr"), body)
        assertTrue(body.contains("Junie $38.50"), body)
    }

    @Test
    fun collapsedBody_countdownAppendedWhenResetKnown() {
        val now = com.angussoftware.fueldashboard.util.epochMillis()
        val m = modelWith(
            quota = listOf(
                FuelStatusModel.QuotaLine(
                    "z.ai", 40,
                    resetsAt = now + (2 * 60 + 15) * 60_000, // 2h 15m
                    available = true,
                ),
            ),
            headline = FuelStatusModel.Headline("z.ai", 40, now + (2 * 60 + 15) * 60_000),
        )
        val body = m.collapsedBodyText("Loading…")
        assertTrue(body.startsWith("z.ai 40% · 2h 1"), body) // 2h 15m ± minute boundary
    }

}
