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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FuelBar(
    remainingPct: Int,
    label: String? = null,
    compact: Boolean = false,
    showHelp: Boolean = false,
) {
    val animatedColor by animateColorAsState(
        targetValue = fuelColor(remainingPct),
        animationSpec = tween(400),
        label = "fuelColor",
    )

    val animatedFraction by animateFloatAsState(
        targetValue = remainingPct.coerceIn(0, 100) / 100f,
        animationSpec = tween(600),
        label = "fuelWidth",
    )

    val barHeight = if (compact) 10.dp else 14.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showHelp) {
                        Spacer(Modifier.width(4.dp))
                        HelpIcon("Remaining quota. Updates every 30 seconds.")
                    }
                }
                Text(
                    text = "$remainingPct%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = animatedColor,
                )
            }
            Spacer(Modifier.height(3.dp))
        }

        // Background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Filled portion (animated width)
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(animatedColor),
            )
        }
    }
}

fun fuelColor(pct: Int): Color {
    val clamped = pct.coerceIn(0, 100)

    // Material Design palette endpoints
    // 100% → Green 500  (0.29, 0.69, 0.31)
    //  50% → Amber 500  (1.00, 0.76, 0.03)
    //   0% → Red 400    (0.94, 0.33, 0.31)

    val green = floatArrayOf(0.29f, 0.69f, 0.31f)
    val amber = floatArrayOf(1.00f, 0.76f, 0.03f)
    val red = floatArrayOf(0.94f, 0.33f, 0.31f)

    val target = if (clamped >= 50) {
        // Interpolate green → amber (100% → 50%)
        val t = (100 - clamped) / 50f
        floatArrayOf(
            green[0] + (amber[0] - green[0]) * t,
            green[1] + (amber[1] - green[1]) * t,
            green[2] + (amber[2] - green[2]) * t,
        )
    } else {
        // Interpolate amber → red (50% → 0%)
        val t = (50 - clamped) / 50f
        floatArrayOf(
            amber[0] + (red[0] - amber[0]) * t,
            amber[1] + (red[1] - amber[1]) * t,
            amber[2] + (red[2] - amber[2]) * t,
        )
    }

    return Color(target[0], target[1], target[2])
}
