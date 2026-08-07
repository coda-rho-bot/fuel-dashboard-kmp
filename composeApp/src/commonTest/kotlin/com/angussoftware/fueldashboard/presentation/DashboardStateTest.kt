package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun junieCreditsAreUncheckedByDefaultAndUseDedicatedSettingsKeys() {
        val state = DashboardState()

        assertNull(state.junieCredits)
        assertNull(state.junieLastChecked)
        assertFalse(state.isCheckingJunie)
        assertEquals("junie_balance", FuelSettingsKeys.JUNIE_BALANCE)
        assertEquals("junie_license", FuelSettingsKeys.JUNIE_LICENSE)
        assertEquals("junie_last_checked", FuelSettingsKeys.JUNIE_LAST_CHECKED)
    }
}