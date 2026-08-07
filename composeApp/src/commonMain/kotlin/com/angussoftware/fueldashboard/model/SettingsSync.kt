package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.settings.ThemeController
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Complete settings snapshot for cross-device sync via QR code or text code.
 *
 * Contains all provider configurations plus theme preferences.
 * Serialized to compact JSON, then either encoded as a QR code or
 * base64-encoded as a copy-paste text code.
 */
@Serializable
data class SettingsSyncData(
    val version: Int = CURRENT_VERSION,
    val providers: List<ProviderConfig>,
    val themeMode: String,
    val lightColorTheme: String,
    val darkColorTheme: String,
    val serverUrl: String? = null,
) {
    companion object {
        const val CURRENT_VERSION = 2

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Build a [SettingsSyncData] from the current app state.
         * @param serverUrl the desktop's public URL (tunnel or LAN) for mobile to connect to
         */
        fun from(
            settings: MultiProviderSettings,
            themeController: ThemeController,
            serverUrl: String? = null,
        ): SettingsSyncData = SettingsSyncData(
            providers = settings.providers,
            themeMode = themeController.themeMode.name,
            lightColorTheme = themeController.lightColorTheme.name,
            darkColorTheme = themeController.darkColorTheme.name,
            serverUrl = serverUrl,
        )

        /**
         * Deserialize from a JSON string (e.g. scanned from a QR code).
         * Returns null if parsing fails.
         */
        fun fromJson(jsonString: String): SettingsSyncData? =
            runCatching {
                json.decodeFromString(serializer(), jsonString)
            }.getOrNull()

        /**
         * Decode a base64 text code (from copy-paste sync) back into [SettingsSyncData].
         *
         * Works on ALL platforms — no camera needed. Returns null if the code
         * is invalid base64 or the decoded JSON doesn't parse.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromCode(code: String): SettingsSyncData? = try {
            fromJson(Base64.decode(code).decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Serialize to compact JSON for QR encoding.
     */
    fun toJson(): String =
        json.encodeToString(serializer(), this)

    /**
     * Encode settings as a base64 text code for copy-paste sync.
     *
     * Works on ALL platforms (desktop, Android, iOS) without a camera.
     * The receiver encodes its JSON representation to UTF-8 bytes, then
     * base64-encodes those bytes.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toCode(): String =
        Base64.encode(toJson().encodeToByteArray())

    /**
     * Wrap providers back into a [MultiProviderSettings].
     */
    fun toMultiProviderSettings(): MultiProviderSettings =
        MultiProviderSettings(providers = providers)
}
