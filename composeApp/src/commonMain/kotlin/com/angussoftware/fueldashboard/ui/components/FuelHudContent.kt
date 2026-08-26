package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Compact status content for the desktop HUD mini-window (and reusable for
 * any small always-visible surface). Runs inside the app's theme, so it
 * matches the user's palette and dark/light mode automatically.
 */
@Composable
fun FuelHudContent(model: FuelStatusModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Title header — fixed "Fuel Status" label, not the provider name
        Text(
            text = "Fuel Status",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))

        // All quota providers as uniform rows — no single-provider emphasis.
        model.quotaLines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(line.remainingPct?.let { "$it%" } ?: "—")
                        FuelStatusModel.formatCountdown(line.resetsAt)?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Credit pools
        model.creditLines.forEach { credit ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = credit.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        credit.dollarBalance != null -> "$${formatRoot("%.2f", credit.dollarBalance)}"
                        credit.creditsTotal != null -> "${credit.creditsTotal} cr"
                        credit.junieBalance != null -> "$${formatRoot("%.2f", credit.junieBalance)}"
                        else -> "—"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (!model.hasAnyData) {
            Text(
                text = "No fuel data yet — add a provider in Settings",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
