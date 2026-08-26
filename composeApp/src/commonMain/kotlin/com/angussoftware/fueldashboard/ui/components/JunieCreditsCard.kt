package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.network.junieCheckUnavailableHint
import com.angussoftware.fueldashboard.util.epochMillis
import com.angussoftware.fueldashboard.util.formatRoot

fun formatJunieLastChecked(lastChecked: Long?, now: Long = epochMillis()): String {
    if (lastChecked == null) return "Never"

    val elapsedMs = (now - lastChecked).coerceAtLeast(0L)
    return when {
        elapsedMs < 60_000L -> "Just now"
        elapsedMs < 60 * 60_000L -> "${elapsedMs / 60_000L}m ago"
        elapsedMs < 24 * 60 * 60_000L -> "${elapsedMs / (60 * 60_000L)}h ago"
        else -> "${elapsedMs / (24 * 60 * 60_000L)}d ago"
    }
}

@Composable
fun JunieProviderBalance(
    report: ProviderReport?,
    isChecking: Boolean,
    onCheckBalance: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = report?.limitDollars?.let { "${formatRoot("%.2f", it)} AI Credits" } ?: "Not checked yet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            report?.detail?.let { license ->
                Text(
                    text = "License: $license",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Last checked: ${formatJunieLastChecked(report?.resetsAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isChecking) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Checking... this costs ~${'$'}0.20",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (onCheckBalance != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCheckBalance,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Check Balance")
                }
                Text(
                    text = "Checking costs ~${'$'}0.20",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = junieCheckUnavailableHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}