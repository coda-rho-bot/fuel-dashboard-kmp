package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.util.epochMillis
import com.angussoftware.fueldashboard.util.formatRoot
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Compact provider tile for the grid view: car-style needle fuel gauge +
 * hourglass sand gauge, maximally dense.
 */

// ---------------------------------------------------------------------------
// Pure math (testable)
// ---------------------------------------------------------------------------

/**
 * Needle angle for a car-style fuel gauge.
 *
 * The gauge sweeps from [START_ANGLE_DEG] (empty, lower-left) to
 * [END_ANGLE_DEG] (full, lower-right). Returns degrees where 0° = 3 o'clock,
 * positive = clockwise (Canvas convention).
 */
fun needleAngleDeg(remainingPct: Int?): Float {
    val clamped = (remainingPct ?: 0).coerceIn(0, 100)
    // +240° sweep from E (150°, down-left) OVER THE TOP to F (390°=30°,
    // down-right). Canvas angles are y-down: 90° points DOWN, so half-full
    // (f=0.5) lands at 270° = straight up (review 1833).
    return START_ANGLE_DEG + (clamped / 100f) * SWEEP_DEG
}

const val START_ANGLE_DEG = 150f   // "E" — pointing down-left
const val SWEEP_DEG = 240f         // over the top; END = 390° ≡ 30° ("F")

/**
 * Sand fraction for the hourglass: how much sand remains in the TOP bulb
 * (= fraction of the quota window remaining). Clamped 0..1. Null when there
 * is no reset countdown.
 */
/**
 * Discretizes a continuous sand fraction into N visible states (10% steps).
 * Snap to the nearest step so the hourglass reads like a gauge with distinct
 * positions rather than a smooth bar — each state is visually distinguishable.
 */
fun discretizeSand(fraction: Float?, steps: Int = 10): Float? {
    if (fraction == null) return null
    val step = 1f / steps
    return (kotlin.math.round(fraction / step) * step).coerceIn(0f, 1f)
}

fun sandFraction(resetsAt: Long?, windowHours: Double, nowMs: Long = epochMillis()): Float? {
    if (resetsAt == null || windowHours <= 0.0) return null
    val totalMs = windowHours * 3_600_000.0
    val remainingMs = resetsAt - nowMs
    if (remainingMs <= 0) return 0f
    return (remainingMs / totalMs).toFloat().coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------
// Gauge composables
// ---------------------------------------------------------------------------

/** Car fuel gauge: arc track, red zone near E, rotating needle, E/F labels. */
@Composable
fun NeedleGauge(remainingPct: Int?, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val redZone = MaterialTheme.colorScheme.error
    val fuelC = com.angussoftware.fueldashboard.ui.components.fuelColor(remainingPct ?: 0)
    val needleColor = fuelC
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val angle = needleAngleDeg(remainingPct)

    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.height * 0.10f, cap = StrokeCap.Round)
        // Height-driven arc box (review 1833): the canvas is short (44dp) and
        // wide; a width-driven box overflows the bottom. Radius ≈ 0.64h keeps
        // the full 240° arc + needle inside the canvas.
        val r = size.height * 0.64f
        val arcSize = Size(r * 2f, r * 2f)
        // Zero-bleed top (review 1834 follow-up): the stroke is centered on
        // the arc path, so a flush-top box still paints half a stroke-width
        // above the canvas. Inset by half the stroke so NO ink escapes the
        // canvas — no reliance on neighboring spacers.
        val halfStroke = size.height * 0.10f / 2f
        val topLeft = Offset((size.width - arcSize.width) / 2f, halfStroke)

        // Full track: from E (150°) sweeping +240° over the top to F (390°).
        drawArc(
            color = trackColor,
            startAngle = START_ANGLE_DEG,
            sweepAngle = SWEEP_DEG,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // Red zone: first 20% of the sweep from E.
        drawArc(
            color = redZone,
            startAngle = START_ANGLE_DEG,
            sweepAngle = 0.20f * SWEEP_DEG,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (remainingPct != null) {
            // Needle from the arc center.
            val cx = topLeft.x + arcSize.width / 2f
            val cy = topLeft.y + arcSize.height / 2f
            val rad = Math.toRadians(angle.toDouble())
            val len = arcSize.width * 0.36f
            drawLine(
                color = needleColor,
                start = Offset(cx, cy),
                end = Offset(cx + (cos(rad) * len).toFloat(), cy + (sin(rad) * len).toFloat()),
                strokeWidth = size.height * 0.055f,
                cap = StrokeCap.Round,
            )
            // Pivot dot.
            drawCircle(color = needleColor, radius = size.height * 0.07f, center = Offset(cx, cy))
        }
    }
    // E / F labels aligned under the arc endpoints: the arc box is centered
    // with r = 0.64h, so the endpoints sit at ~25% inset from each edge.
    // Padding the row inward keeps the labels visually under the gauge arc
    // instead of pinned to the full tile width.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("E", style = MaterialTheme.typography.labelSmall, color = redZone)
        Text("F", style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

/** Hourglass: sand in the top bulb = fraction of the window remaining. */
@Composable
fun HourglassGauge(sandFraction: Float?, modifier: Modifier = Modifier) {
    val frameColor = MaterialTheme.colorScheme.outline
    // Sand uses the SAME timerColor system as the list view's TimerBar:
    // purple (fresh, time remaining) → blue (mid) → cyan (nearly expired).
    // sandFraction is remaining/total; timerColor takes elapsed fraction,
    // so we pass (1 - sandFraction). Visually distinct from the needle's
    // green-amber-red fuel gradient.
    val sandColor = timerColor(1f - (sandFraction ?: 0f))
    Canvas(modifier = modifier) {
        if (sandFraction == null) return@Canvas
        val w = size.width
        val h = size.height
        val waistY = h * 0.5f

        // Frame: two triangles apex-to-apex.
        val frameStroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, miter = 1f)
        // Top bulb triangle: top-left, top-right, waist.
        drawLine(frameColor, Offset(w * 0.08f, h * 0.06f), Offset(w * 0.92f, h * 0.06f), frameStroke.width, StrokeCap.Round)
        drawLine(frameColor, Offset(w * 0.08f, h * 0.06f), Offset(w * 0.5f, waistY), frameStroke.width, StrokeCap.Round)
        drawLine(frameColor, Offset(w * 0.92f, h * 0.06f), Offset(w * 0.5f, waistY), frameStroke.width, StrokeCap.Round)
        // Bottom bulb triangle: waist, bottom-left, bottom-right.
        drawLine(frameColor, Offset(w * 0.5f, waistY), Offset(w * 0.08f, h * 0.94f), frameStroke.width, StrokeCap.Round)
        drawLine(frameColor, Offset(w * 0.5f, waistY), Offset(w * 0.92f, h * 0.94f), frameStroke.width, StrokeCap.Round)
        drawLine(frameColor, Offset(w * 0.08f, h * 0.94f), Offset(w * 0.92f, h * 0.94f), frameStroke.width, StrokeCap.Round)

        // Sand rendering: half-widths computed from the ACTUAL triangle
        // geometry (frame vertices), inset by the stroke half-width so sand
        // stays flush inside the glass without overlapping the frame lines.
        //
        // Top bulb triangle: (0.08w, 0.06h) — (0.92w, 0.06h) — (0.5w, waistY)
        //   At height y, interior half-width = 0.42w * (1 - (y - 0.06h) / 0.44h)
        // Bottom bulb triangle: (0.5w, waistY) — (0.08w, 0.94h) — (0.92w, 0.94h)
        //   At height y, interior half-width = 0.42w * ((y - waistY) / 0.44h)
        val frameHalfStroke = frameStroke.width / 2f
        val topStart = h * 0.06f
        val bottomEnd = h * 0.94f

        fun topBulbHalfW(y: Float): Float {
            val t = ((y - topStart) / (waistY - topStart)).coerceIn(0f, 1f)
            return (w * 0.42f * (1f - t) - frameHalfStroke).coerceAtLeast(0f)
        }

        fun bottomBulbHalfW(y: Float): Float {
            val t = ((y - waistY) / (bottomEnd - waistY)).coerceIn(0f, 1f)
            return (w * 0.42f * t - frameHalfStroke).coerceAtLeast(0f)
        }

        // Sand levels use CUBIC mapping to match triangular volume geometry.
        // Linear height fill in a triangle misrepresents volume: filling 50%
        // of height in an inverted triangle captures ~87% of volume (the
        // bottom is wide). Square root (area-based) mapping corrects this:
        //   Top bulb: d = H * sandFraction^(1/2)   (height above waist)
        //   Bottom bulb: h = H * (1 - (1-fill)^(1/2))  (height from bottom)

        // Sand in the top bulb: volume = sandFraction of total.
        if (sandFraction > 0f) {
            val H_top = waistY - topStart
            val dFromWaist = H_top * Math.pow(sandFraction.toDouble(), 1.0 / 2.0).toFloat()
            val levelY = waistY - dFromWaist
            var y = levelY
            val step = (waistY - topStart) / 24f
            while (y < waistY) {
                val halfW = topBulbHalfW(y)
                if (halfW > 0f) {
                    drawLine(sandColor, Offset(w * 0.5f - halfW, y), Offset(w * 0.5f + halfW, y), step * 1.4f, StrokeCap.Butt)
                }
                y += step
            }
        }
        // Sand in the bottom bulb: volume = (1 - sandFraction) of total.
        if (sandFraction < 1f) {
            val bottomFill = (1f - sandFraction)
            val H_bottom = bottomEnd - waistY
            // Height from bottom: h = H * (1 - (1-fill)^(1/3))
            val hFromBottom = H_bottom * (1f - Math.pow((1f - bottomFill).toDouble(), 1.0 / 2.0).toFloat())
            val topOfSand = bottomEnd - hFromBottom
            var y = topOfSand
            val step = (bottomEnd - waistY) / 20f
            while (y < bottomEnd) {
                val halfW = bottomBulbHalfW(y)
                if (halfW > 0f) {
                    drawLine(sandColor, Offset(w * 0.5f - halfW, y), Offset(w * 0.5f + halfW, y), step * 1.4f, StrokeCap.Butt)
                }
                y += step
            }
        }
        // Falling stream: visible while draining, width tapers as sand
        // depletes (thick when much sand falling, thin near the end).
        if (sandFraction in 0.01f..0.99f) {
            val streamWidth = w * (0.03f + 0.04f * sandFraction)
            drawLine(sandColor, Offset(w * 0.5f, waistY - h * 0.02f), Offset(w * 0.5f, waistY + h * 0.10f), streamWidth, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------
// Grid tile
// ---------------------------------------------------------------------------

/**
 * Compact provider tile for grid view. Needle gauge for percentage, hourglass
 * for reset countdown, degraded to a prominent dollar figure for
 * balance-only providers.
 */
@Composable
fun GridProviderTile(
    config: ProviderConfig,
    report: ProviderReport?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .padding(10.dp)
                .fillMaxWidth()
                .heightIn(min = 140.dp),
        ) {
            // Name + availability dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    config.displayName.ifBlank { config.kind.displayName },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                val dot = when {
                    report == null -> MaterialTheme.colorScheme.outline
                    report.available -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
                Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            }
            Spacer(Modifier.height(4.dp))

            if (report?.remainingPct != null) {
                // Needle + hourglass side by side
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeedleGauge(
                            remainingPct = report.remainingPct,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        )
                    }
                    val sand = discretizeSand(sandFraction(report.resetsAt, report.windowHours))
                    if (sand != null) {
                        Spacer(Modifier.width(10.dp))
                        HourglassGauge(sandFraction = sand, modifier = Modifier.size(36.dp, 54.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${report.remainingPct}% full",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (report.remainingPct < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    report.resetsAt?.let {
                        val mins = ((it - epochMillis()) / 60_000).coerceAtLeast(0)
                        val timeText = if (mins >= 60) "${mins / 60}h ${mins % 60}m til reset" else "${mins}m til reset"
                        Text(
                            timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (report?.isPrepaidCreditPool == true && report.limitDollars != null) {
                // Prepaid balance: prominent figure
                Text(
                    formatRoot("$%.2f", report.limitDollars),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (report.limitDollars < 1.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text("credit remaining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (report != null) {
                // Spend-only / no gauge: compact rawDisplay
                Text(
                    report.rawDisplay.ifBlank { "No gauge data" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            } else {
                Text("connecting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
