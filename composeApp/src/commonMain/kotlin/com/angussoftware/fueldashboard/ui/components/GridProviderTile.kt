package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    val needleColor = if ((remainingPct ?: 0) < 20) redZone else MaterialTheme.colorScheme.primary
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
    // E / F labels via tiny overlay row (Canvas text is heavy; compose labels are cleaner)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("E", style = MaterialTheme.typography.labelSmall, color = if ((remainingPct ?: 0) < 20) redZone else labelColor)
        Text("F", style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

/** Hourglass: sand in the top bulb = fraction of the window remaining. */
@Composable
fun HourglassGauge(sandFraction: Float?, modifier: Modifier = Modifier) {
    val frameColor = MaterialTheme.colorScheme.outline
    val sandColor = MaterialTheme.colorScheme.tertiary
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

        // Sand in the top bulb: level from the top = sandFraction.
        // The bulb interior at height y has half-width interpolating from
        // 0.42w (top) to ~0 (waist).
        if (sandFraction > 0f) {
            val levelY = h * 0.10f + (1f - sandFraction) * (waistY - h * 0.10f)
            var y = levelY
            val step = (waistY - h * 0.10f) / 24f
            while (y < waistY) {
                val t = (y - h * 0.10f) / (waistY - h * 0.10f)          // 0 at top, 1 at waist
                val halfW = w * (0.40f * (1f - t))
                drawLine(sandColor, Offset(w * 0.5f - halfW, y), Offset(w * 0.5f + halfW, y), step * 1.4f, StrokeCap.Butt)
                y += step
            }
        }
        // Sand in the bottom bulb: always some if sand has fallen.
        if (sandFraction < 1f) {
            val bottomFill = (1f - sandFraction)
            val topOfSand = waistY + (h * 0.88f - waistY) * (1f - bottomFill)
            var y = topOfSand
            val step = (h * 0.88f - waistY) / 20f
            while (y < h * 0.90f) {
                val t = 1f - (y - waistY) / (h * 0.88f - waistY)        // 1 at waist, 0 at bottom
                val halfW = w * (0.40f * t)
                drawLine(sandColor, Offset(w * 0.5f - halfW, y), Offset(w * 0.5f + halfW, y), step * 1.4f, StrokeCap.Butt)
                y += step
            }
        }
        // Falling stream while running.
        if (sandFraction in 0.01f..0.99f) {
            drawLine(sandColor, Offset(w * 0.5f, waistY - h * 0.02f), Offset(w * 0.5f, waistY + h * 0.10f), w * 0.05f, StrokeCap.Round)
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
        Column(Modifier.padding(10.dp)) {
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
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        )
                    }
                    val sand = sandFraction(report.resetsAt, report.windowHours)
                    if (sand != null) {
                        Spacer(Modifier.width(10.dp))
                        HourglassGauge(sandFraction = sand, modifier = Modifier.size(26.dp, 40.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${report.remainingPct}%" + (report.resetsAt?.let {
                        val mins = ((it - epochMillis()) / 60_000).coerceAtLeast(0)
                        if (mins >= 60) " · ${mins / 60}h ${mins % 60}m" else " · ${mins}m"
                    } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (report.remainingPct < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
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
