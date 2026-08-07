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
}