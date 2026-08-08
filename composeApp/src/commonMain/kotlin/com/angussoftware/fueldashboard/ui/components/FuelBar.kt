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
    // R: 0 at 100%, ramps to 255 at 50%, stays 255 below 50%
    val r = ((100 - clamped) / 50f).coerceIn(0f, 1f)
    // G: 255 above 50%, ramps from 255 to 0 between 50% and 0%
    val g = (clamped / 50f).coerceIn(0f, 1f)
    return Color(r, g, 0f)
}
