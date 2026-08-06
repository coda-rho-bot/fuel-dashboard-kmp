package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.model.ProviderCategory

/**
 * Import entry dialog with two options: Scan QR code (camera) or Paste Code (text input).
 *
 * Works on ALL platforms — the paste-code option requires no camera.
 * On platforms without a camera, the Scan QR button is hidden.
 *
 * @param canScanQr Whether the QR scanner is available on this platform
 * @param onScanQr Called when the user chooses to scan a QR code
 * @param onImportCode Called with the parsed [SettingsSyncData] from a pasted code
 * @param onDismiss Called when the dialog is cancelled
 */
@Composable
fun ImportEntryDialog(
    canScanQr: Boolean,
    onScanQr: () -> Unit,
    onImportCode: (SettingsSyncData) -> Unit,
    onDismiss: () -> Unit,
) {
    var codeText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Import Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // --- Option 1: Scan QR (Android only) ---
                if (canScanQr) {
                    Text(
                        text = "Scan QR Code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Use your camera to scan a QR code from another device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onScanQr()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR Code")
                    }

                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.height(1.dp).fillMaxWidth().clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Or paste a code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        text = "Paste code from another device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Option 2: Paste Code (all platforms) ---
                OutlinedTextField(
                    value = codeText,
                    onValueChange = {
                        codeText = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste code from another device") },
                    placeholder = { Text("eJztw ...") },
                    singleLine = false,
                    maxLines = 4,
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    isError = error != null,
                    supportingText = error?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )

                Spacer(Modifier.height(12.dp))

                // Import button for pasted code
                OutlinedButton(
                    onClick = {
                        val trimmed = codeText.trim()
                        if (trimmed.isEmpty()) {
                            error = "Paste a code first"
                        } else {
                            val parsed = SettingsSyncData.fromCode(trimmed)
                            if (parsed == null) {
                                error = "Invalid code — could not decode settings"
                            } else {
                                onDismiss()
                                onImportCode(parsed)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = codeText.isNotBlank(),
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Import from Code")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Confirmation dialog shown after scanning a QR code or pasting a code and parsing settings.
 *
 * Shows a summary of what will be imported and asks for confirmation before
 * replacing all current settings.
 *
 * @param syncData The parsed settings data to import
 * @param onConfirm Called when the user confirms the import
 * @param onDismiss Called when the user cancels
 */
@Composable
fun ImportSettingsDialog(
    syncData: SettingsSyncData,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val llmProviders = syncData.providers.filter { it.kind.category == ProviderCategory.LLM_PROVIDER }
    val fleetProviders = syncData.providers.filter { it.kind.category == ProviderCategory.AGENT_BACKEND }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Import Settings?") },
        text = {
            Column {
                Text(
                    text = "This will replace your current settings with:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))

                // Provider summary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${syncData.providers.size} provider${if (syncData.providers.size != 1) "s" else ""}" +
                            if (llmProviders.isNotEmpty()) " (${llmProviders.size} LLM, ${fleetProviders.size} agent)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // List provider names
                syncData.providers.take(5).forEach { provider ->
                    Text(
                        text = "  - ${provider.resolvedDisplayName()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                    )
                }
                if (syncData.providers.size > 5) {
                    Text(
                        text = "  ...and ${syncData.providers.size - 5} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Theme summary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Theme: ${syncData.themeMode.lowercase()}" +
                            " (light: ${syncData.lightColorTheme}, dark: ${syncData.darkColorTheme})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Warning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "This will replace all current settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Import & Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
