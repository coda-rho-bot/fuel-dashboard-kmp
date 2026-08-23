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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.SettingsSyncData

/**
 * Dialog that shows sync options for transferring settings to another device.
 *
 * Two sync methods are presented:
 * 1. **QR Code** — scan with phone camera (desktop → mobile)
 * 2. **Text Code** — base64-encoded string, copy on one device, paste on another (ALL platforms)
 *
 * @param syncData The complete settings data to encode
 * @param onDismiss Called when the dialog is closed
 */
@Composable
fun QrSyncDialog(
    syncData: SettingsSyncData,
    onDismiss: () -> Unit,
) {
    val code = syncData.toCode()
    val json = syncData.toQrData()
    val capacity = estimateQrCapacity(json)
    val clipboardManager = LocalClipboardManager.current
    val (title, subtitle) = when (syncData.scope) {
        SettingsSyncData.SCOPE_AGENTS ->
            "Sync Agents" to "Scan with your phone to sync agent configurations (full launcher details included)"
        SettingsSyncData.SCOPE_SETTINGS ->
            "Sync Settings" to "Scan with your phone to sync providers, connection, and preferences (agents sync separately)"
        else ->
            "Sync Settings" to "Scan with your phone to sync everything"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (capacity.tooLarge) {
                    Text(
                        text = "Settings data is too large for a QR code (${capacity.byteLength} bytes, max ~2,953).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    QrCodeCanvas(
                        data = json,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (capacity.version > 0) {
                        Text(
                            text = "QR version ${capacity.version} (${capacity.byteLength} bytes)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Security warning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
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
                        text = "This QR code contains your API keys. Only scan on devices you trust.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.height(1.dp).fillMaxWidth().clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                Spacer(Modifier.height(12.dp))

                // --- Text code section ---
                Text(
                    text = "Or copy this code and paste on another device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 280.dp),
                    readOnly = true,
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    trailingIcon = {
                        TextButton(onClick = { clipboardManager.setText(AnnotatedString(code)) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
