package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.ModelDrainRateDisplay
import kotlin.math.roundToInt

/**
 * Displays per-model fuel consumption data measured from real gauge drops.
 * Shows which models are burning the most quota.
 */
@Composable
fun ModelDrainRatesPanel(
    rates: List<ModelDrainRateDisplay>,
    modifier: Modifier = Modifier,
) {
    if (rates.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Model Consumption",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            HelpIcon("Measured fuel consumption attributed to each model based on when fuel dropped while that model was active")
        }
        Spacer(Modifier.height(8.dp))

        val maxConsumed = rates.maxOfOrNull { it.totalFuelConsumed } ?: 1.0

        rates.forEach { rate ->
            ModelDrainRow(rate, maxConsumed)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun ModelDrainRow(
    rate: ModelDrainRateDisplay,
    maxConsumed: Double,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rate.model,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${rate.totalFuelConsumed.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(2.dp))
        // Bar showing relative consumption
        LinearProgressIndicator(
            progress = { (rate.totalFuelConsumed / maxConsumed).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.error,
            trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "~${String.format("%.1f", rate.avgDrainPerHr)}% / hr · ${rate.sampleCount} samples",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
