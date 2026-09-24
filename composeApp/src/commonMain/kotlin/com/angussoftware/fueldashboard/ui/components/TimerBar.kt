package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.delay

/**
 * A horizontal timer gauge that shows time remaining until quota reset.
 *
 * The bar fills from left to right as time elapses (empty = just reset, full = about to reset).
 * Color interpolates purple → blue → cyan (fresh reset = purple, almost expired = cyan).
 *
 * @param resetsAt     Epoch ms when the quota window resets
 * @param windowMs     Total window duration in ms (e.g. 5h = 18_000_000)
 */
@Composable
fun TimerBar(
    resetsAt: Long,
    windowMs: Long,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tick = epochMillis()
        }
    }

    val now = tick
    val remainingMs = (resetsAt - now).coerceAtLeast(0L)

    // A window whose total length is unknown has no meaningful progress to
    // show: we know WHEN it resets, not how far through it we are. Dividing by
    // zero here yielded Infinity, clamped to a permanently full bar — a
    // confident-looking fill carrying no information. Providers that report a
    // reset without a period (z.ai credit rows, Junie) hit this.
    //
    // sandFraction() already guards the same case for the grid tiles; this
    // brings the detail view in line.
    val lengthKnown = windowMs > 0L
    val remainingFraction = if (lengthKnown) {
        (remainingMs.toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val animatedColor by animateColorAsState(
        targetValue = timerColor(1f - remainingFraction),
        animationSpec = tween(400),
        label = "timerColor",
    )
    val animatedFraction by animateFloatAsState(
        targetValue = remainingFraction,
        animationSpec = tween(600),
        label = "timerWidth",
    )

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatCountdown(resetsAt, now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )

        }
        // The track is drawn either way so rows stay aligned; only the fill is
        // withheld when there is no proportion to represent.
        if (lengthKnown) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(animatedColor),
                )
            }
        }
    }
}

/**
 * Timer color: purple (fresh, 0% elapsed) → blue (mid, 50%) → cyan (almost expired, 100%).
 *
 * Uses muted Material-palette-inspired endpoints:
 *   Purple 400 → Blue 400 → Cyan 400
 */
fun timerColor(elapsedFraction: Float): Color {
    val purple = floatArrayOf(0.50f, 0.33f, 0.70f)  // muted Purple 400
    val blue = floatArrayOf(0.20f, 0.45f, 0.75f)    // muted Blue 400
    val cyan = floatArrayOf(0.15f, 0.55f, 0.60f)    // muted Cyan 400

    val target = if (elapsedFraction <= 0.5f) {
        val t = elapsedFraction / 0.5f
        floatArrayOf(
            purple[0] + (blue[0] - purple[0]) * t,
            purple[1] + (blue[1] - purple[1]) * t,
            purple[2] + (blue[2] - purple[2]) * t,
        )
    } else {
        val t = (elapsedFraction - 0.5f) / 0.5f
        floatArrayOf(
            blue[0] + (cyan[0] - blue[0]) * t,
            blue[1] + (cyan[1] - blue[1]) * t,
            blue[2] + (cyan[2] - blue[2]) * t,
        )
    }

    return Color(target[0], target[1], target[2])
}
