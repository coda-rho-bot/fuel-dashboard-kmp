package com.angussoftware.fueldashboard.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardSnapshotTest {

    @Test
    fun snapshotContainsEveryDisplaySection() {
        val state = DashboardState(
            lastUpdated = 1_000L,
            burnRate = 7.5,
            fuelAdvice = FuelAdvisor.Advice.Surplus(
                FuelAdvisor.QuotaRegime(
                    windowsAnalyzed = 34, exhaustions = 1,
                    projectedPctAtReset = 55.0, burnPctPerHr = 7.5, resetInMs = 10_800_000L,
                ),
            ),
            meteredBySource24h = listOf(MeteredUsageDisplay("Coda", 1000, 100, 5, 42.0)),
            wasteByProvider = listOf(
                FuelIntelligence.ProviderWaste(
                    providerId = "zai", providerName = "z.ai", windowMs = 5 * 3_600_000L,
                    daily = listOf(
                        FuelIntelligence.DailyWaste(
                            dayStart = 1_000L, windows = 5, observed = 5, estimated = 0,
                            wastedPctAvg = 62.0, anyExhausted = true,
                        ),
                    ),
                    wastedPctAvg = 62.0,
                ),
            ),
            fuelEvents = listOf(
                FuelIntelligence.FuelEvent(
                    2_000L, FuelIntelligence.FuelEventType.FUEL_DROP, "Fuel dropped 4.0%",
                ),
            ),
        )

        val snap = DashboardSnapshot.build(state)

        // Every UI-visible section present
        assertNotNull(snap["providers"], "providers")
        assertNotNull(snap["fuel"], "fuel")
        assertNotNull(snap["advisor"], "advisor")
        assertNotNull(snap["usage"], "usage")
        assertNotNull(snap["waste"], "waste")
        assertNotNull(snap["fuel_events"], "fuel_events")
        assertNotNull(snap["model_drain_rates"], "drain rates")
        assertNotNull(snap["agents"], "agents")
        assertNotNull(snap["agent_model_usage_24h"], "agent model usage")
        assertNotNull(snap["ingestion"], "ingestion")

        // Spot-check values
        val advisor = snap["advisor"]!!.toString()
        assertTrue(advisor.contains("\"surplus\""), advisor)
        val usage = snap["usage"]!!.toString()
        assertTrue(usage.contains("\"Coda\""), usage)
        val waste = snap["waste"]!!.toString()
        assertTrue(waste.contains("62.0"), waste)
    }

    @Test
    fun snapshotExcludesSecrets() {
        val state = DashboardState(lastUpdated = 1L)
        val snap = DashboardSnapshot.build(state).toString()
        // No settings/API key leakage — settings section not serialized at all
        assertTrue(!snap.contains("apiKey"), snap.take(200))
        assertTrue(!snap.contains("serverApiKey"), snap.take(200))
    }
}
