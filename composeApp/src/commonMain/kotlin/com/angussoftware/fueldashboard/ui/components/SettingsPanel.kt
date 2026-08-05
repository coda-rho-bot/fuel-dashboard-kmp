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
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
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
import com.angussoftware.fueldashboard.model.FuelProvider
import com.angussoftware.fueldashboard.model.FuelSettings
import com.angussoftware.fueldashboard.model.FuelSourceMode
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
fun SettingsPanel(
    themeController: ThemeController,
    settings: FuelSettings,
    onSettingsChange: (FuelSettings) -> Unit,
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

            // --- Fuel Source Mode ---
            FuelSourceModeSection(settings, onSettingsChange)

            Spacer(Modifier.height(12.dp))

            // --- Mode-specific config ---
            when (settings.mode) {
                FuelSourceMode.DIRECT -> DirectModeSection(settings, onSettingsChange)
                FuelSourceMode.CONNECTED -> ApiUrlSection(
                    currentApiUrl = settings.orchestratorUrl,
                    onApiUrlChange = { url ->
                        onSettingsChange(settings.copy(orchestratorUrl = url))
                    },
                )
            }

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
// Fuel Source Mode Selector
// ---------------------------------------------------------------------------

@Composable
private fun FuelSourceModeSection(
    settings: FuelSettings,
    onSettingsChange: (FuelSettings) -> Unit,
) {
    Text(
        text = "Fuel Source",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FuelSourceChip(
            label = "Direct",
            icon = Icons.Default.Key,
            isSelected = settings.mode == FuelSourceMode.DIRECT,
            onClick = { onSettingsChange(settings.copy(mode = FuelSourceMode.DIRECT)) },
            modifier = Modifier.weight(1f),
        )
        FuelSourceChip(
            label = "Connected",
            icon = Icons.Default.Cloud,
            isSelected = settings.mode == FuelSourceMode.CONNECTED,
            onClick = { onSettingsChange(settings.copy(mode = FuelSourceMode.CONNECTED)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FuelSourceChip(
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
// Direct Mode Config
// ---------------------------------------------------------------------------

@Composable
private fun DirectModeSection(
    settings: FuelSettings,
    onSettingsChange: (FuelSettings) -> Unit,
) {
    // Provider selector (only z.ai for now, but structured for expansion)
    Text(
        text = "Provider",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FuelProvider.entries.forEach { provider ->
            val isSelected = settings.provider == provider
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
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(containerColor)
                    .clickable { onSettingsChange(settings.copy(provider = provider)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // API key input
    ApiKeySection(
        currentKey = settings.providerApiKey,
        onKeyChange = { key -> onSettingsChange(settings.copy(providerApiKey = key)) },
    )
}

@Composable
private fun ApiKeySection(
    currentKey: String,
    onKeyChange: (String) -> Unit,
) {
    var localKey by remember(currentKey) { mutableStateOf(currentKey) }
    var isEditing by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    Text(
        text = "Provider API Key",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))

    if (isEditing) {
        OutlinedTextField(
            value = localKey,
            onValueChange = { localKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        onKeyChange(localKey)
                        isEditing = false
                    }) {
                        Text("Save")
                    }
                }
            },
        )
        TextButton(onClick = {
            localKey = currentKey
            isEditing = false
        }) {
            Text("Cancel", style = MaterialTheme.typography.labelSmall)
        }
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
                imageVector = Icons.Default.Api,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (currentKey.isBlank()) "Not set" else "\u2022".repeat(12),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (currentKey.isBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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

// ---------------------------------------------------------------------------
// Theme sections (unchanged from original)
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
// Color Theme Picker — mode-aware with separate light/dark sections
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

/**
 * A labelled list of theme options with the selected theme highlighted.
 *
 * When [showWarningIfMismatch] is true, a warning indicator is shown if the selected
 * theme doesn't match the section's intended mode (e.g., a dark theme selected in the
 * "Light Themes" section).
 */
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

@Composable
private fun ApiUrlSection(
    currentApiUrl: String,
    onApiUrlChange: (String) -> Unit,
) {
    var localUrl by remember(currentApiUrl) { mutableStateOf(currentApiUrl) }
    var isEditing by remember { mutableStateOf(false) }

    Text(
        text = "API Server",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))

    if (isEditing) {
        OutlinedTextField(
            value = localUrl,
            onValueChange = { localUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                TextButton(onClick = {
                    onApiUrlChange(localUrl)
                    isEditing = false
                }) {
                    Text("Save")
                }
            },
        )
        TextButton(onClick = {
            localUrl = currentApiUrl
            isEditing = false
        }) {
            Text("Cancel", style = MaterialTheme.typography.labelSmall)
        }
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
                text = currentApiUrl,
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
