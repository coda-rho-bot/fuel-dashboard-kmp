package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.settings.UsageSourcesStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.usage.UsageSourcesSettings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Complete settings snapshot for cross-device sync via QR code or text code.
 *
 * Contains provider and agent configurations plus theme preferences.
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
    val serverApiKey: String? = null,
    val agentSettings: AgentSettings = AgentSettings(),
    val junieBalance: Double? = null,
    val junieLicense: String? = null,
    val junieLastChecked: Long? = null,
    // Section ordering (Usage/Intel tabs). Empty = receiver keeps its own.
    val usageSectionOrder: List<String> = emptyList(),
    val intelSectionOrder: List<String> = emptyList(),
    // Usage ingestion sources (Letta server config). Null = unset/default —
    // dropped from the QR payload by the compact encoder.
    val usageSources: com.angussoftware.fueldashboard.usage.UsageSourcesSettings? = null,
    // Preferences — null = receiver keeps its own.
    val eventDropThresholdPct: Double? = null,
    val showHelp: Boolean? = null,
    val showThemeIcon: Boolean? = null,
    // Custom feedback endpoints — null when at baked defaults.
    val feedbackUrl: String? = null,
    val feedbackRepo: String? = null,
) {
    companion object {
        const val CURRENT_VERSION = 5

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Compact encoder for QR transport — drops null/default fields to
         *  keep the payload under the reliably-scannable QR version bound
         *  (≤20).  Receiver fills missing fields from defaults. */
        private val qrJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /**
         * Build a [SettingsSyncData] from the current app state.
         * @param serverUrl the desktop's public URL (tunnel or LAN) for mobile to connect to
         */
        fun from(
            settings: MultiProviderSettings,
            agentSettings: AgentSettings,
            themeController: ThemeController,
            serverUrl: String? = null,
            serverApiKey: String? = null,
            junieBalance: Double? = null,
            junieLicense: String? = null,
            junieLastChecked: Long? = null,
        ): SettingsSyncData = SettingsSyncData(
            providers = settings.providers,
            themeMode = themeController.themeMode.name,
            lightColorTheme = themeController.lightColorTheme.name,
            darkColorTheme = themeController.darkColorTheme.name,
            serverUrl = serverUrl,
            serverApiKey = serverApiKey,
            agentSettings = agentSettings,
            junieBalance = junieBalance,
            junieLicense = junieLicense,
            junieLastChecked = junieLastChecked,
            usageSectionOrder = com.angussoftware.fueldashboard.settings.SectionOrder.loadUsage(),
            intelSectionOrder = com.angussoftware.fueldashboard.settings.SectionOrder.loadIntel(),
            usageSources = UsageSourcesStore.load()
                .takeIf { it != UsageSourcesSettings() },
            eventDropThresholdPct = loadStringSetting(FuelSettingsKeys.EVENT_DROP_THRESHOLD, "").toDoubleOrNull(),
            showHelp = loadStringSetting(FuelSettingsKeys.SHOW_HELP, "").ifBlank { null }?.toBoolean(),
            showThemeIcon = loadStringSetting(FuelSettingsKeys.SHOW_THEME_ICON, "").ifBlank { null }?.toBoolean(),
            feedbackUrl = loadStringSetting(FuelSettingsKeys.FEEDBACK_URL, "").takeIf { it.isNotBlank() },
            feedbackRepo = loadStringSetting(FuelSettingsKeys.FEEDBACK_REPO, "").takeIf { it.isNotBlank() },
        )

        /**
         * Deserialize from a JSON string.
         */
        fun fromJson(jsonString: String): SettingsSyncData? =
            runCatching {
                json.decodeFromString(serializer(), jsonString)
            }.getOrNull()

        /**
         * Decompress + decode QR data (compressed via gzip + base64).
         * Falls back to raw JSON if the data isn't compressed.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromQrData(qrData: String): SettingsSyncData? {
            // Try compressed decode first
            val compressed = runCatching {
                fromJson(decompress(Base64.decode(qrData)))
            }.getOrNull()
            if (compressed != null) return compressed

            // Fallback: raw JSON (backwards compatibility)
            return fromJson(qrData)
        }

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
     *
     * The JSON is also available via [toJson]. For QR codes, use [toQrData]
     * which compresses the data to reduce QR density.
     */
    fun toJson(): String =
        json.encodeToString(serializer(), this)

    /**
     * Slimmed copy for QR transport: agent launcher fields (command paths,
     * localhost socket URLs, env maps) are desktop-local and worthless on the
     * receiving phone — mobile only renders agent id/name — but they dominate
     * the payload and push the QR into marginally-scannable versions (21+).
     *
     * The copy-paste text code ([toCode]) keeps full fidelity, since bytes
     * are free there.
     */
    fun slimmedForQr(): SettingsSyncData = copy(
        agentSettings = AgentSettings(
            agents = agentSettings.agents.map { config ->
                config.copy(command = "", args = "", env = emptyMap())
            },
        ),
        // Small preference fields stay out of the QR to respect the reliably-
        // scannable version bound (≤20) — the receiver keeps its own values.
        // The text code ([toCode]) carries them at full fidelity.
        eventDropThresholdPct = null,
        showHelp = null,
        showThemeIcon = null,
        feedbackUrl = null,
        feedbackRepo = null,
    )

    /**
     * Compressed + base64-encoded data for QR codes.
     * Reduces QR density by ~50% for better scannability.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toQrData(): String {
        val encoded = qrJson.encodeToString(serializer(), slimmedForQr())
        return Base64.encode(compress(encoded))
    }

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

/** Platform-specific gzip compression (expect). */
expect fun compress(data: String): ByteArray

/** Platform-specific gzip decompression (expect). */
expect fun decompress(data: ByteArray): String
