package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.settings.UsageSourcesStore
import com.angussoftware.fueldashboard.usage.IngestionStatus
import com.angussoftware.fueldashboard.usage.LettaSourceConfig
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Usage Sources settings section — configures pull-side ingestion connectors.
 *
 * The Letta connector polls a Letta server (cloud or self-hosted) for per-run
 * token usage and meters it into the universal usage store. All platform
 * specifics live in the connector; this UI only edits its config.
 */
@Composable
fun UsageSourcesSection(
    status: IngestionStatus,
    modifier: Modifier = Modifier,
) {
    var config by remember { mutableStateOf(UsageSourcesStore.load().letta) }
    var statusLine by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Usage Sources",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Pull-side metering: polls platforms that track token usage server-side " +
                "and meters it into the universal usage store.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // --- Letta connector ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Letta (runs polling)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (config.enabled)
                        "Polls /v1/runs every 5 min; attributes tokens per agent & model"
                    else
                        "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { enabled ->
                    config = config.copy(enabled = enabled)
                    UsageSourcesStore.saveLetta(config)
                    statusLine = if (enabled) "Enabled — polling starts within 5 min" else "Disabled"
                },
            )
        }

        if (config.enabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { url ->
                    config = config.copy(baseUrl = url)
                    UsageSourcesStore.saveLetta(config)
                },
                label = { Text("Server URL") },
                placeholder = { Text("https://api.letta.com") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { key ->
                    config = config.copy(apiKey = key)
                    UsageSourcesStore.saveLetta(config)
                },
                label = { Text("API key") },
                placeholder = { Text("sk-let-… (app.letta.com → API keys)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --- Live status ---
        if (config.enabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val healthy = status.lastError == null && status.lastPollAt != null
                Icon(
                    imageVector = if (healthy) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = when {
                        status.lastError != null -> MaterialTheme.colorScheme.error
                        healthy -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 6.dp).height(16.dp).width(16.dp),
                )
                val lastPoll = status.lastPollAt?.let {
                    SimpleDateFormat("HH:mm:ss").format(Date(it))
                } ?: "never"
                Text(
                    text = buildString {
                        append("Last poll: $lastPoll")
                        append("  ·  Records: ${status.totalIngested}")
                        status.lastError?.let { append("\n$it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.lastError != null)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusLine.isNotEmpty()) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
