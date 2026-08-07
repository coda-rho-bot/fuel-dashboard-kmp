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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RecommendationBanner(
    recommendedModel: String,
    burnRate: Double,
    surplusAlert: Boolean,
    showHelp: Boolean,
) {
    val bannerColor = when {
        surplusAlert -> MaterialTheme.colorScheme.tertiaryContainer
        burnRate > 20 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onBannerColor = when {
        surplusAlert -> MaterialTheme.colorScheme.onTertiaryContainer
        burnRate > 20 -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bannerColor)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Recommended Model",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBannerColor,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Best model based on your current fuel levels")
                }
            }
            Text(
                text = "${burnRate.format(1)}% / hr",
                style = MaterialTheme.typography.bodySmall,
                color = onBannerColor,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = recommendedModel,
            style = MaterialTheme.typography.headlineSmall,
            color = onBannerColor,
            fontWeight = FontWeight.Bold,
        )
        if (surplusAlert) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Surplus capacity available",
                style = MaterialTheme.typography.bodySmall,
                color = onBannerColor,
            )
        }
    }
}

private fun Double.format(decimals: Int): String {
    val multiplier = Math.pow(10.0, decimals.toDouble()).toInt()
    val rounded = (kotlin.math.round(this * multiplier) / multiplier)
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
