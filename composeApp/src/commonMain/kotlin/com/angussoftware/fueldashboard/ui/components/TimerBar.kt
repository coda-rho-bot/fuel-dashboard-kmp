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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.delay

/**
 * A horizontal timer gauge that shows time remaining until quota reset.
 *
 * The bar fills from left to right as time elapses (empty = just reset, full = about to reset).
 * Color interpolates along the theme's accents: primary (fresh reset) →
 * tertiary (almost expired). Theme-derived — see [GaugeColors].
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
