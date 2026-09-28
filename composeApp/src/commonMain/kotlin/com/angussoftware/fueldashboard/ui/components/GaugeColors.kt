package com.angussoftware.fueldashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Theme-derived gauge ramps.
 *
 * The gauges used to hardcode their endpoints (green/amber/red for fuel,
 * purple/blue/cyan for timers) — fixed colors that ignored the selected
 * theme entirely. A Dracula dashboard showed the same traffic-light greens
 * as the Angus brand theme, which read as a bug, not a brand.
 *
 * Now the anchors come from the theme's own semantic roles and everything
 * between them is derived:
 *
 *  - Fuel: [ColorScheme.primary] (full) → [ColorScheme.error] (empty),
 *    traveling **decreasing hue** — the direction the classic
 *    green→amber→red ramp takes (red 0° → green 120° numbering; the W3C
 *    CSS Color 4 `interpolate-hue: decreasing` convention). For every
 *    scheme the theming library ships, that arc passes through amber
 *    territory, so "warning in the middle" emerges from the math rather
 *    than from a constant. On the Angus brand palette the familiar
 *    green→amber→red reappears; Dracula gets purple→…→red because that
 *    is what Dracula is.
 *  - Timer: [ColorScheme.primary] (fresh) → [ColorScheme.tertiary]
 *    (expiring) along the **shorter** hue arc — two accents the theme
 *    author chose. Timer expiry is not an error, so [ColorScheme.error]
 *    is deliberately left to the fuel ramp. Themes whose primary and
 *    tertiary sit close on the wheel get a subtle timer; that is the
 *    theme's sensibility, honored.
 *
 * Interpolation happens in OKLCH (perceptually uniform): lightness and
 * chroma move linearly, hue moves along the chosen arc, and the result is
 * gamut-clamped back to sRGB by reducing chroma — never by crushing
 * lightness.
 */
internal enum class HueDirection { Shorter, Decreasing, Increasing }

/** A color in OKLCH space. [h] is radians; achromatic colors carry c ≈ 0. */
internal class Oklch(val l: Float, val c: Float, val h: Float)

private const val TAU = (2.0 * Math.PI).toFloat()

/** Positive modulo into [0, [TAU]). */
private fun positiveMod(x: Float): Float = ((x % TAU) + TAU) % TAU

/**
 * Signed hue delta from [from] to [to]:
 *  - [HueDirection.Shorter]: the arc in (−π, +π] — shortest way round.
 *  - [HueDirection.Decreasing]: always clockwise-down the wheel (≤ 0).
 *  - [HueDirection.Increasing]: always up the wheel (≥ 0).
 */
internal fun hueDelta(from: Float, to: Float, direction: HueDirection): Float = when (direction) {
    HueDirection.Shorter -> atan2(sin(to - from), cos(to - from))
    HueDirection.Decreasing -> -positiveMod(from - to)
    HueDirection.Increasing -> positiveMod(to - from)
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float =
    if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

/** sRGB → OKLCH (Björn Ottosson's reference matrices). */
internal fun colorToOklch(color: Color): Oklch {
    val r = srgbToLinear(color.red)
    val g = srgbToLinear(color.green)
    val b = srgbToLinear(color.blue)

    val l_ = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val m_ = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val s_ = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

    val l = l_.pow(1f / 3f)
    val m = m_.pow(1f / 3f)
    val s = s_.pow(1f / 3f)

    val lightness = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s

    return Oklch(lightness, sqrt(a * a + bb * bb), atan2(bb, a))
}

/**
 * OKLCH → sRGB, gamut-clamped by shrinking chroma (never lightness) until
 * the color fits. Out-of-gamut requests (vivid anchors, mid-ramp blends)
 * land on the most saturated in-gamut color of the same lightness and hue.
 */
internal fun oklchToColor(lch: Oklch): Color {
    var c = lch.c
    repeat(32) {
        val a = c * cos(lch.h)
        val b = c * sin(lch.h)
        val l = lch.l + 0.3963377774f * a + 0.2158037573f * b
        val m = lch.l - 0.1055613458f * a - 0.0638541728f * b
        val s = lch.l - 0.0894841775f * a - 1.2914855480f * b

        val l_ = l * l * l
        val m_ = m * m * m
        val s_ = s * s * s

        val r = 4.0767416621f * l_ - 3.3077115913f * m_ + 0.2309699292f * s_
        val g = -1.2684380046f * l_ + 2.6097574011f * m_ - 0.3413193965f * s_
        val bl = -0.0041960863f * l_ - 0.7034186147f * m_ + 1.7076147010f * s_

        if (r in 0f..1f && g in 0f..1f && bl in 0f..1f) {
            return Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
        }
        c *= 0.96f
    }
    // Even L=0 gray out of gamut (shouldn't happen) — fall back to the
    // achromatic color of the requested lightness.
    val achromatic = lch.l.coerceIn(0f, 1f)
    return Color(achromatic, achromatic, achromatic)
}

/**
 * Interpolates [from] → [to] at fraction [t] in OKLCH, walking hue along
 * [direction]. Lightness and chroma lerp linearly, so the ramp's perceived
 * brightness slides smoothly between the anchors' own.
 */
internal fun rampColor(t: Float, from: Color, to: Color, direction: HueDirection): Color {
    val a = colorToOklch(from)
    val b = colorToOklch(to)
    val delta = hueDelta(a.h, b.h, direction)
    return oklchToColor(
        Oklch(
            l = a.l + (b.l - a.l) * t,
            c = a.c + (b.c - a.c) * t,
            h = a.h + delta * t,
        ),
    )
}

/**
 * Fuel-gauge ramp, pure and testable: [full] (theme primary) → [empty]
 * (theme error) as `pct` falls 100 → 0. The 50% midpoint is derived, not
 * specified — on wheel positions where decreasing hue passes amber, that
 * midpoint lands in warning territory automatically.
 */
fun fuelGaugeColor(pct: Int, full: Color, empty: Color): Color {
    val clamped = pct.coerceIn(0, 100)
    val drained = (100 - clamped) / 100f
    val mid = rampColor(0.5f, full, empty, HueDirection.Decreasing)
    return if (drained <= 0.5f) {
        rampColor(drained / 0.5f, full, mid, HueDirection.Shorter)
    } else {
        rampColor((drained - 0.5f) / 0.5f, mid, empty, HueDirection.Shorter)
    }
}

/**
 * Timer-gauge ramp, pure and testable: [fresh] (theme primary) → [expired]
 * (theme tertiary) as the window elapses 0 → 1. Shorter hue arc — time
 * passing is not a warning, so the ramp simply walks the theme's accent
 * span.
 */
fun timerGaugeColor(elapsedFraction: Float, fresh: Color, expired: Color): Color {
    val t = elapsedFraction.coerceIn(0f, 1f)
    val mid = rampColor(0.5f, fresh, expired, HueDirection.Shorter)
    return if (t <= 0.5f) {
        rampColor(t / 0.5f, fresh, mid, HueDirection.Shorter)
    } else {
        rampColor((t - 0.5f) / 0.5f, mid, expired, HueDirection.Shorter)
    }
}

/** Theme-wired fuel color: primary (full) → error (empty), decreasing hue. */
@Composable
fun fuelColor(pct: Int): Color =
    fuelGaugeColor(pct, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)

/** Theme-wired timer color: primary (fresh) → tertiary (expiring), shorter arc. */
@Composable
fun timerColor(elapsedFraction: Float): Color =
    timerGaugeColor(elapsedFraction, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
