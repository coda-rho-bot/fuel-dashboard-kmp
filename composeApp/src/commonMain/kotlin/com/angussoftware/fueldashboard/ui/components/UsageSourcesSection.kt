package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TextButton
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
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.UsageSourcesStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.usage.IngestionStatus
import com.angussoftware.fueldashboard.usage.LettaSourceConfig

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
    // Section collapses when the connector is off; the state persists so the
    // page doesn't re-expand something the user folded away.
    var isCollapsed by remember {
        mutableStateOf(
            loadStringSetting(FuelSettingsKeys.COLLAPSED_USAGE, (!config.enabled).toString()).toBoolean(),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        CollapsibleSectionHeader(
            title = "Usage Sources",
            isCollapsed = isCollapsed,
            onToggle = {
                isCollapsed = !isCollapsed
                saveStringSetting(FuelSettingsKeys.COLLAPSED_USAGE, isCollapsed.toString())
            },
            trailing = {
                Text(
                    text = if (config.enabled) "Letta: on" else "off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        AnimatedVisibility(
            visible = !isCollapsed,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
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
                    if (enabled && isCollapsed) {
                        isCollapsed = false
                        saveStringSetting(FuelSettingsKeys.COLLAPSED_USAGE, "false")
                    }
                },
            )
        }

        if (config.enabled) {
            Spacer(Modifier.height(8.dp))
            // Draft-and-save: text fields edit local state; Save commits to
            // the store in one write (no partial URLs reaching the connector).
            var urlDraft by remember(config.enabled) { mutableStateOf(config.baseUrl) }
            var keyDraft by remember(config.enabled) { mutableStateOf(config.apiKey) }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://api.letta.com") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("API key") },
                placeholder = { Text("sk-let-… (app.letta.com → API keys)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    urlDraft = config.baseUrl
                    keyDraft = config.apiKey
                }) {
                    Text("Reset")
                }
                TextButton(onClick = {
                    config = config.copy(baseUrl = urlDraft.trim(), apiKey = keyDraft.trim())
                    UsageSourcesStore.saveLetta(config)
                    statusLine = "Saved — applies on next poll"
                }) {
                    Text("Save")
                }
            }
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
                    val local = Instant.fromEpochMilliseconds(it)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                    "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
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
    }
}
