package com.angussoftware.fueldashboard.settings

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [SectionOrder.move] — including the hidden-section skip:
 * swapping past an invisible panel must land on the next visible one,
 * not silently no-op.
 */
class SectionOrderMoveTest {

    @BeforeTest
    fun setUp() {
        java.util.prefs.Preferences.userRoot().node("fuel-dashboard").clear()
    }

    @Test
    fun moveUpSwapsWithAdjacent() {
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "waste",
            -1,
        )
        assertEquals(listOf("metered", "waste", "drain"), order)
    }

    @Test
    fun moveDownSwapsWithAdjacent() {
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "metered",
            +1,
        )
        assertEquals(listOf("drain", "metered", "waste"), order)
    }

    @Test
    fun moveUpSkipsHiddenSection() {
        // "drain" hidden (no data) — waste moving up must land ABOVE
        // drain (i.e. to the top), not swap with the invisible panel.
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "waste",
            -1,
            isVisible = { it != "drain" },
        )
        assertEquals(listOf("waste", "metered", "drain"), order)
    }

    @Test
    fun moveDownSkipsHiddenSection() {
        // metered moving down past hidden drain → bottom position
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "metered",
            +1,
            isVisible = { it != "drain" },
        )
        assertEquals(listOf("drain", "waste", "metered"), order)
    }

    @Test
    fun moveBeyondVisibleEdgeIsNoOp() {
        // metered is the only visible section above waste when drain is
        // hidden; moving waste up twice: first lands at top, second no-ops.
        SectionOrder.move(FuelSettingsKeys.SECTION_ORDER_USAGE, SectionOrder.USAGE_KEYS, "waste", -1, isVisible = { it != "drain" })
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "waste",
            -1,
            isVisible = { it != "drain" },
        )
        assertEquals(listOf("waste", "metered", "drain"), order)
    }

    @Test
    fun allOthersHiddenMoveIsNoOp() {
        // Only drain visible; moving drain anywhere is a no-op.
        val order = SectionOrder.move(
            FuelSettingsKeys.SECTION_ORDER_USAGE,
            SectionOrder.USAGE_KEYS,
            "drain",
            -1,
            isVisible = { it == "drain" },
        )
        assertEquals(SectionOrder.USAGE_KEYS, order)
    }
}
