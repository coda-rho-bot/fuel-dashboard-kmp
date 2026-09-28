package com.angussoftware.fueldashboard.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Theme-derived gauge ramps — the derivation is the contract.
 *
 * Anchors come from the theme's roles (primary/error for fuel,
 * primary/tertiary for timers); everything between is math. These tests
 * pin the math: hue direction per the CSS Color 4 vocabulary, endpoint
 * fidelity, monotonic arcs, and degenerate anchors (achromatic, clamped)
 * producing colors rather than NaNs.
 */
class GaugeColorsTest {

    // ── hue direction (CSS Color 4: increasing/decreasing/shorter) ──────

    @Test
    fun shorterArcTakesTheShortWayRound() {
        // Red (0 rad) → green (≈2.09 rad): shorter is +120° through yellow,
        // never −240° through magenta/blue/cyan.
        val d = hueDelta(0f, 2.0944f, HueDirection.Shorter)
        assertTrue(abs(d - 2.0944f) < 0.01f, "shorter arc red→green should be +120°, was ${Math.toDegrees(d.toDouble())}°")
        // And the reverse is the negation.
        val back = hueDelta(2.0944f, 0f, HueDirection.Shorter)
        assertTrue(abs(back + 2.0944f) < 0.01f, "shorter arc green→red should be −120°")
    }

    @Test
    fun decreasingHueNeverGoesUpTheWheel() {
        // Green → red decreasing must go green→yellow→red (−120°), not the
        // long way through cyan/blue/magenta (+240°).
        val d = hueDelta(2.0944f, 0f, HueDirection.Decreasing)
        assertTrue(abs(d + 2.0944f) < 0.01f, "decreasing green→red should be −120°, was ${Math.toDegrees(d.toDouble())}°")
        // Red → green decreasing wraps: −240° through magenta/blue/cyan.
        val wrapped = hueDelta(0f, 2.0944f, HueDirection.Decreasing)
        assertTrue(abs(wrapped + 4.1888f) < 0.01f, "decreasing red→green should be −240°, was ${Math.toDegrees(wrapped.toDouble())}°")
        // Never positive, including exact same hue.
        assertTrue(hueDelta(0f, 0f, HueDirection.Decreasing) == 0f)
    }

    // ── OKLCH round trip ────────────────────────────────────────────────

    @Test
    fun oklchRoundTripsInGamutColors() {
        // Anchors from real schemes (Dracula primary, brand error).
        for (c in listOf(Color(0xFF550AC1), Color(0xFFFDB0D4), Color(0xFF8CE3E2), Color(0xFF884A6A))) {
            val back = oklchToColor(colorToOklch(c))
            assertTrue(abs(back.red - c.red) < 0.01f, "red channel drifted: ${back.red} vs ${c.red}")
            assertTrue(abs(back.green - c.green) < 0.01f, "green channel drifted: ${back.green} vs ${c.green}")
            assertTrue(abs(back.blue - c.blue) < 0.01f, "blue channel drifted: ${back.blue} vs ${c.blue}")
        }
    }

    // ── fuel ramp ────────────────────────────────────────────────────────

    /** Angus brand dark scheme: cyan-teal primary, rose error. */
    private val brandFull = Color(0xFF8CE3E2)
    private val brandEmpty = Color(0xFFFDB0D4)

    @Test
    fun fuelRampHitsItsAnchors() {
        val atFull = fuelGaugeColor(100, brandFull, brandEmpty)
        val atEmpty = fuelGaugeColor(0, brandFull, brandEmpty)
        assertTrue(abs(atFull.red - brandFull.red) < 0.02f && abs(atFull.green - brandFull.green) < 0.02f, "100% should be the primary anchor")
        assertTrue(abs(atEmpty.red - brandEmpty.red) < 0.02f, "0% should be the error anchor, was $atEmpty")
    }

    @Test
    fun fuelRampWalksDecreasingHueMonotonically() {
        // The contract: fuel drains through the warning middle, i.e. hue
        // (OKLCH) decreases — or holds — at every step. No backtracking.
        var prev = colorToOklch(fuelGaugeColor(100, brandFull, brandEmpty)).h
        for (pct in 95 downTo 0 step 5) {
            val h = colorToOklch(fuelGaugeColor(pct, brandFull, brandEmpty)).h
            val d = hueDelta(h, prev, HueDirection.Shorter) // prev - h, signed
            assertTrue(d >= -0.001f, "hue backtracked at $pct%: ${Math.toDegrees(prev.toDouble())}° → ${Math.toDegrees(h.toDouble())}°")
            prev = h
        }
    }

    @Test
    fun brandFuelMidpointLandsInWarningTerritory() {
        // With brand anchors (teal → rose, decreasing), the 50% midpoint
        // must sit strictly between them on the wheel — not equal to either
        // anchor's hue. That's the derived-warning property: the middle is
        // neither "full" nor "empty", by construction.
        val mid = colorToOklch(fuelGaugeColor(50, brandFull, brandEmpty))
        val full = colorToOklch(brandFull)
        val empty = colorToOklch(brandEmpty)
        assertTrue(abs(hueDelta(mid.h, full.h, HueDirection.Shorter)) > 0.05f, "midpoint hue ≈ full anchor")
        assertTrue(abs(hueDelta(mid.h, empty.h, HueDirection.Shorter)) > 0.05f, "midpoint hue ≈ empty anchor")
    }

    @Test
    fun fuelRampOnClassicEndpointsReproducesTrafficLight() {
        // The historical constants (green→red): the derivation must travel
        // through amber on the way down — the old behavior emerged from
        // endpoints, and it still does.
        val green = Color(0xFF4AB04F)
        val red = Color(0xFFF05448)
        val mid = fuelGaugeColor(50, green, red)
        val midO = colorToOklch(mid)
        // Amber in OKLCH sits near h ≈ 1.9 rad (~110°); green ≈ 2.5 (~145°),
        // red ≈ 0.5 (~29°). Assert the midpoint hue lies between red and
        // green through yellow, closer to neither endpoint.
        val dFromFull = hueDelta(midO.h, colorToOklch(green).h, HueDirection.Shorter)
        val dToEmpty = hueDelta(colorToOklch(red).h, midO.h, HueDirection.Shorter)
        assertTrue(abs(dFromFull - dToEmpty) < 1.2f, "midpoint should sit mid-arc, deltas $dFromFull / $dToEmpty")
    }

    // ── timer ramp ───────────────────────────────────────────────────────

    private val timerFresh = Color(0xFF8CE3E2) // brand primary
    private val timerExpired = Color(0xFFA0CAFD) // brand tertiary (blue)

    @Test
    fun timerRampHitsItsAnchors() {
        val at0 = timerGaugeColor(0f, timerFresh, timerExpired)
        val at1 = timerGaugeColor(1f, timerFresh, timerExpired)
        assertTrue(abs(at0.red - timerFresh.red) < 0.02f && abs(at0.blue - timerFresh.blue) < 0.02f, "elapsed 0 should be primary")
        assertTrue(abs(at1.blue - timerExpired.blue) < 0.02f, "elapsed 1 should be tertiary, was $at1")
    }

    @Test
    fun timerRampClampsOutOfRangeFractions() {
        assertEquals(
            timerGaugeColor(0f, timerFresh, timerExpired),
            timerGaugeColor(-0.5f, timerFresh, timerExpired),
            "negative elapsed clamps to fresh",
        )
        assertEquals(
            timerGaugeColor(1f, timerFresh, timerExpired),
            timerGaugeColor(2f, timerFresh, timerExpired),
            "over-elapsed clamps to expired",
        )
    }

    // ── degenerate anchors ───────────────────────────────────────────────

    @Test
    fun achromaticAnchorsProduceColorsNotNaN() {
        // A monochrome theme's primary could be near-gray. hue is undefined
        // (atan2(0,0) = 0) — the ramp must still produce valid colors and
        // hit the anchors' lightness.
        val gray = Color(0xFF808080)
        val dark = Color(0xFF303030)
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val c = rampColor(t, gray, dark, HueDirection.Decreasing)
            assertTrue(!c.red.isNaN() && !c.green.isNaN() && !c.blue.isNaN(), "NaN at t=$t")
            assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f, "out of range at t=$t")
        }
    }

    @Test
    fun outOfGamutMidblendClampsToValidSrgb() {
        // Vivid anchors whose OKLCH midpoint overshoots sRGB: the clamp
        // shrinks chroma, never emits garbage.
        val vividA = Color(0xFF00FF00)
        val vividB = Color(0xFFFF0080)
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val c = fuelGaugeColor(pct, vividA, vividB)
            assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f, "out of sRGB at $pct%")
        }
    }
}
