package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardStateTest {
    @Test
    fun helpIsEnabledByDefaultAndUsesTheExpectedSettingsKey() {
        assertTrue(DashboardState().showHelp)
        assertEquals("showHelp", FuelSettingsKeys.SHOW_HELP)
    }

    @Test
    fun helpCanBeHiddenInDashboardState() {
        assertEquals(false, DashboardState(showHelp = false).showHelp)
    }

    @Test
    fun manualProviderChecksAreEmptyByDefaultAndUseJunieCacheKeys() {
        val state = DashboardState()

        assertTrue(state.checkingProviderIds.isEmpty())
        assertEquals("junie_balance", FuelSettingsKeys.JUNIE_BALANCE)
        assertEquals("junie_license", FuelSettingsKeys.JUNIE_LICENSE)
        assertEquals("junie_last_checked", FuelSettingsKeys.JUNIE_LAST_CHECKED)
    }

    @Test
    fun failureBackoffDoublesIntervalAndCapsAt30Minutes() {
        val vm = FuelViewModel()
        // Healthy: the configured interval.
        assertEquals(60_000L, vm.effectiveIntervalMs(60, 0))
        // Each consecutive failure doubles the interval.
        assertEquals(120_000L, vm.effectiveIntervalMs(60, 1))
        assertEquals(240_000L, vm.effectiveIntervalMs(60, 2))
        // Multiplier caps at x32 — visible with a base where x32 stays
        // under the 30-min ceiling (30s x 32 = 16 min).
        assertEquals(30_000L * 32, vm.effectiveIntervalMs(30, 5))
        assertEquals(30_000L * 32, vm.effectiveIntervalMs(30, 9))
        // …and the backoff never exceeds 30 minutes — but a user-configured
        // base above 30 min is honored as-is (the ceiling constrains
        // backoff, not the chosen cadence).
        assertEquals(30 * 60_000L, vm.effectiveIntervalMs(60, 6))
        assertEquals(3_600_000L, vm.effectiveIntervalMs(3600, 0))
        assertEquals(3_600_000L, vm.effectiveIntervalMs(3600, 4))
    }
}
