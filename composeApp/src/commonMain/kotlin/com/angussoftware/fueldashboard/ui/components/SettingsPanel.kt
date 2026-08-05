package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
fun SettingsPanel(
    themeController: ThemeController,
    settings: MultiProviderSettings,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- Providers section ---
            ProvidersSection(
                settings = settings,
                viewModel = viewModel,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Orchestrator section ---
            OrchestratorSection(
                settings = settings,
                viewModel = viewModel,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Theme mode toggle ---
            ThemeModeSection(themeController)

            Spacer(Modifier.height(12.dp))

            // --- Color theme picker ---
            ColorThemePicker(themeController)
        }
    }
}

// ---------------------------------------------------------------------------
// Providers Section
// ---------------------------------------------------------------------------

@Composable
private fun ProvidersSection(
    settings: MultiProviderSettings,
    viewModel: FuelViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Providers",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showAddDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add provider", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add", style = MaterialTheme.typography.labelSmall)
        }
    }

    Spacer(Modifier.height(8.dp))

    if (settings.providers.isEmpty()) {
        Text(
            text = "No providers configured. Click \"+ Add\" to set up a fuel provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        settings.providers.forEach { config ->
            ProviderConfigRow(
                config = config,
                onUpdate = { viewModel.updateProvider(it) },
                onRemove = { viewModel.removeProvider(config.id) },
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    // Add provider dialog
    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { kind, apiKey, name, url ->
                viewModel.addProvider(kind, apiKey, name, url)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun ProviderConfigRow(
    config: ProviderConfig,
    onUpdate: (ProviderConfig) -> Unit,
    onRemove: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var localKey by remember(config.id, isEditing) { mutableStateOf(config.apiKey) }
    var localName by remember(config.id, isEditing) { mutableStateOf(config.displayName) }
    var localUrl by remember(config.id, isEditing) { mutableStateOf(config.serverUrl) }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (config.kind) {
                        ProviderKind.ZAI -> Icons.Default.Cloud
                        ProviderKind.LETTA_CLOUD -> Icons.Default.Cloud
                        ProviderKind.OPENAI -> Icons.Default.Api
                        ProviderKind.ANTHROPIC -> Icons.Default.Api
                        ProviderKind.DEEPSEEK -> Icons.Default.Api
                        ProviderKind.GROQ -> Icons.Default.Api
                        ProviderKind.MISTRAL -> Icons.Default.Api
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.resolvedDisplayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = config.kind.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (config.isConfigured) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Configured",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { isEditing = !isEditing }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (isEditing) {
                Spacer(Modifier.height(8.dp))

                // Display name
                OutlinedTextField(
                    value = localName,
                    onValueChange = { localName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display Name (optional)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))

                // API key
                OutlinedTextField(
                    value = localKey,
                    onValueChange = { localKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))

                // Server URL (optional override)
                OutlinedTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server URL (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            config.resolvedServerUrl(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        localKey = config.apiKey
                        localName = config.displayName
                        localUrl = config.serverUrl
                        isEditing = false
                    }) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        onUpdate(config.copy(
                            apiKey = localKey.trim(),
                            displayName = localName.trim(),
                            serverUrl = localUrl.trim(),
                        ))
                        isEditing = false
                    }) {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (!config.isConfigured) {
                Text(
                    text = "API key required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderKind, String, String, String) -> Unit,
) {
    var selectedKind by remember { mutableStateOf(ProviderKind.ZAI) }
    var apiKey by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Add Provider",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            // Provider type selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderKind.entries.forEach { kind ->
                    val isSelected = selectedKind == kind
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { selectedKind = kind }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = kind.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display Name (optional)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${selectedKind.displayName} API Key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(
                        when (selectedKind) {
                            ProviderKind.ZAI -> "https://api.z.ai"
                            ProviderKind.LETTA_CLOUD -> "https://api.letta.com"
                            ProviderKind.OPENAI -> "https://api.openai.com"
                            ProviderKind.ANTHROPIC -> "https://api.anthropic.com"
                            ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
                            ProviderKind.GROQ -> "https://api.groq.com/openai"
                            ProviderKind.MISTRAL -> "https://api.mistral.ai"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onAdd(selectedKind, apiKey.trim(), displayName.trim(), serverUrl.trim()) },
                    enabled = apiKey.isNotBlank(),
                ) {
                    Text("Add Provider")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Orchestrator Section
// ---------------------------------------------------------------------------

@Composable
private fun OrchestratorSection(
    settings: MultiProviderSettings,
    viewModel: FuelViewModel,
) {
    var isEditing by remember(settings.orchestratorUrl) { mutableStateOf(false) }
    var localUrl by remember(settings.orchestratorUrl) { mutableStateOf(settings.orchestratorUrl) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Orchestrator",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (settings.orchestratorEnabled) "Connected" else "Disabled",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.orchestratorEnabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (settings.orchestratorEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable {
                    viewModel.updateSettings(settings.copy(orchestratorEnabled = !settings.orchestratorEnabled))
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (settings.orchestratorEnabled) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                color = if (settings.orchestratorEnabled)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (settings.orchestratorEnabled) {
        Spacer(Modifier.height(8.dp))
        if (isEditing) {
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                trailingIcon = {
                    Row {
                        TextButton(onClick = {
                            viewModel.updateSettings(settings.copy(orchestratorUrl = localUrl.trim()))
                            isEditing = false
                        }) { Text("Save") }
                    }
                },
            )
            TextButton(onClick = {
                localUrl = settings.orchestratorUrl
                isEditing = false
            }) { Text("Cancel", style = MaterialTheme.typography.labelSmall) }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { isEditing = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = settings.orchestratorUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Theme sections (unchanged)
// ---------------------------------------------------------------------------

@Composable
private fun ThemeModeSection(themeController: ThemeController) {
    Text(
        text = "Appearance",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeModeChip(
            label = "System",
            icon = Icons.Default.SettingsApplications,
            isSelected = themeController.themeMode == ThemeMode.SYSTEM,
            onClick = { themeController.updateThemeMode(ThemeMode.SYSTEM) },
            modifier = Modifier.weight(1f),
        )
        ThemeModeChip(
            label = "Light",
            icon = Icons.Default.LightMode,
            isSelected = themeController.themeMode == ThemeMode.LIGHT,
            onClick = { themeController.updateThemeMode(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f),
        )
        ThemeModeChip(
            label = "Dark",
            icon = Icons.Default.DarkMode,
            isSelected = themeController.themeMode == ThemeMode.DARK,
            onClick = { themeController.updateThemeMode(ThemeMode.DARK) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// Color Theme Picker
// ---------------------------------------------------------------------------

@Composable
private fun ColorThemePicker(themeController: ThemeController) {
    var isExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Color Theme",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = activeThemeSummary(themeController),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        when (themeController.themeMode) {
            ThemeMode.LIGHT -> ThemeOptionList(
                label = "Light Themes",
                options = ColorTheme.lightOptions,
                selected = themeController.lightColorTheme,
                showWarningIfMismatch = false,
                isForLightMode = true,
                onSelect = { themeController.updateLightColorTheme(it) },
            )

            ThemeMode.DARK -> ThemeOptionList(
                label = "Dark Themes",
                options = ColorTheme.darkOptions,
                selected = themeController.darkColorTheme,
                showWarningIfMismatch = false,
                isForLightMode = false,
                onSelect = { themeController.updateDarkColorTheme(it) },
            )

            ThemeMode.SYSTEM -> Column {
                ThemeOptionList(
                    label = "Light Themes",
                    options = ColorTheme.allWithNames,
                    selected = themeController.lightColorTheme,
                    showWarningIfMismatch = true,
                    isForLightMode = true,
                    onSelect = { themeController.updateLightColorTheme(it) },
                )
                Spacer(Modifier.height(8.dp))
                ThemeOptionList(
                    label = "Dark Themes",
                    options = ColorTheme.allWithNames,
                    selected = themeController.darkColorTheme,
                    showWarningIfMismatch = true,
                    isForLightMode = false,
                    onSelect = { themeController.updateDarkColorTheme(it) },
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionList(
    label: String,
    options: List<Pair<ColorTheme, String>>,
    selected: ColorTheme,
    showWarningIfMismatch: Boolean,
    isForLightMode: Boolean,
    onSelect: (ColorTheme) -> Unit,
) {
    val hasMismatch = showWarningIfMismatch && selected.let {
        if (isForLightMode) !it.isLightTheme else !it.isDarkTheme
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasMismatch) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Theme mismatch warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            items(options) { (theme, name) ->
                ColorThemeRow(
                    name = name,
                    isSelected = theme == selected,
                    onClick = { onSelect(theme) },
                )
            }
        }
    }
}

@Composable
private fun ColorThemeRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun activeThemeSummary(controller: ThemeController): String {
    val lightName = themeDisplayName(controller.lightColorTheme)
    val darkName = themeDisplayName(controller.darkColorTheme)
    return if (controller.lightColorTheme == controller.darkColorTheme) {
        lightName
    } else {
        "$lightName / $darkName"
    }
}

private fun themeDisplayName(theme: ColorTheme): String {
    return ColorTheme.allWithNames.find { it.first == theme }?.second ?: theme.name
}
