package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.settings.ThemeController
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Complete settings snapshot for cross-device sync via QR code.
 *
 * Contains all provider configurations plus theme preferences.
 * Serialized to compact JSON and encoded as a QR code.
 */
@Serializable
data class SettingsSyncData(
    val version: Int = CURRENT_VERSION,
    val providers: List<ProviderConfig>,
    val themeMode: String,
    val lightColorTheme: String,
    val darkColorTheme: String,
) {
    companion object {
        const val CURRENT_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Build a [SettingsSyncData] from the current app state.
         */
        fun from(
            settings: MultiProviderSettings,
            themeController: ThemeController,
        ): SettingsSyncData = SettingsSyncData(
            providers = settings.providers,
            themeMode = themeController.themeMode.name,
            lightColorTheme = themeController.lightColorTheme.name,
            darkColorTheme = themeController.darkColorTheme.name,
        )

        /**
         * Deserialize from a JSON string (e.g. scanned from a QR code).
         * Returns null if parsing fails.
         */
        fun fromJson(jsonString: String): SettingsSyncData? =
            runCatching {
                json.decodeFromString(serializer(), jsonString)
            }.getOrNull()
    }

    /**
     * Serialize to compact JSON for QR encoding.
     */
    fun toJson(): String =
        json.encodeToString(serializer(), this)

    /**
     * Wrap providers back into a [MultiProviderSettings].
     */
    fun toMultiProviderSettings(): MultiProviderSettings =
        MultiProviderSettings(providers = providers)
}
