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
    /**
     * What this payload carries — routes the import so a scoped payload
     * (QR split by domain) only applies its own fields:
     * - [SCOPE_FULL]: everything (text code, server import, legacy QR)
     * - [SCOPE_SETTINGS]: everything EXCEPT agent configs
     * - [SCOPE_AGENTS]: ONLY agent configs (full launcher fidelity)
     */
    val scope: String = SCOPE_FULL,
    val providers: List<ProviderConfig>,
    val themeMode: String = "SYSTEM",
    val lightColorTheme: String = "Default",
    val darkColorTheme: String = "Default",
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

        /** Everything — text code and server-side import payloads. */
        const val SCOPE_FULL = "full"

        /** Settings-only QR — providers, connection, usage sources, prefs. */
        const val SCOPE_SETTINGS = "settings"

        /** Agents-only QR — agent configs at FULL launcher fidelity. */
        const val SCOPE_AGENTS = "agents"

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
            // Sync section orders ONLY when the user actually reordered —
            // empty lists mean "receiver keeps its own" and cost zero QR bytes.
            usageSectionOrder = com.angussoftware.fueldashboard.settings.SectionOrder.loadUsage()
                .takeIf { it != com.angussoftware.fueldashboard.settings.SectionOrder.USAGE_KEYS }
                ?: emptyList(),
            intelSectionOrder = com.angussoftware.fueldashboard.settings.SectionOrder.loadIntel()
                .takeIf { it != com.angussoftware.fueldashboard.settings.SectionOrder.INTEL_KEYS }
                ?: emptyList(),
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
            // Current format: gzip + Base45 (QR-native alphabet — alphanumeric
            // mode packs 5.5 bits/char vs byte mode's 8)
            Base45.decode(qrData)?.let { bytes ->
                runCatching { fromJson(decompress(bytes)) }.getOrNull()?.let { return it }
            }
            // Legacy: gzip + Base64 (senders before Base45)
            val legacy = runCatching {
                fromJson(decompress(Base64.decode(qrData)))
            }.getOrNull()
            if (legacy != null) return legacy

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
     * Settings-domain payload for the settings QR code: everything except
     * agent configs. Small enough to carry the preference fields un-slimmed
     * (agents were the bulk of the old combined payload).
     */
    fun forSettingsQr(): SettingsSyncData = copy(
        scope = SCOPE_SETTINGS,
        agentSettings = AgentSettings(),
    )

    /**
     * Agents-domain payload for the agents QR code: ONLY agent configs,
     * with FULL launcher fidelity (command/args/env ride this code — useful
     * desktop→desktop, gracefully ignored by phones). Connection and
     * settings fields are nulled so an agents scan never touches them.
     */
    fun forAgentsQr(): SettingsSyncData = copy(
        scope = SCOPE_AGENTS,
        providers = emptyList(),
        // Theme fields are required-but-ignored for agents-scope imports;
        // canonical defaults let the QR encoder (encodeDefaults=false)
        // drop them instead of paying dead bytes.
        themeMode = "SYSTEM",
        lightColorTheme = "Default",
        darkColorTheme = "Default",
        serverUrl = null,
        serverApiKey = null,
        junieBalance = null,
        junieLicense = null,
        junieLastChecked = null,
        usageSectionOrder = emptyList(),
        intelSectionOrder = emptyList(),
        usageSources = null,
        eventDropThresholdPct = null,
        showHelp = null,
        showThemeIcon = null,
        feedbackUrl = null,
        feedbackRepo = null,
    )

    /**
     * QR-transport slimming, scope-aware: agents QR keeps full launcher
     * fidelity by design; settings QR has nothing to slim; legacy full-scope
     * QRs strip agent launcher fields AND small prefs to respect the
     * reliably-scannable bound (version ≤ 20). Text codes ([toCode]) are
     * never slimmed.
     */
    fun slimmedForQr(): SettingsSyncData = when (scope) {
        // Agents QR ships full launcher fidelity on purpose.
        SCOPE_AGENTS -> this
        // Settings QR has no agents; scoped payloads stay un-slimmed.
        SCOPE_SETTINGS -> this
        // Legacy combined QR (if anyone generates one): strip launcher fields
        // AND the small prefs to respect the reliably-scannable bound (≤20).
        else -> copy(
            agentSettings = AgentSettings(
                agents = agentSettings.agents.map { config ->
                    config.copy(command = "", args = "", env = emptyMap())
                },
            ),
            eventDropThresholdPct = null,
            showHelp = null,
            showThemeIcon = null,
            feedbackUrl = null,
            feedbackRepo = null,
        )
    }

    /**
     * Compressed + Base45-encoded data for QR codes.
     *
     * Base45's alphabet fits QR alphanumeric mode (5.5 bits/char vs byte
     * mode's 8) — ~23% fewer modules than base64 for the same payload.
     * Decoders fall back to base64/raw for older senders.
     */
    fun toQrData(): String {
        val encoded = qrJson.encodeToString(serializer(), slimmedForQr())
        return Base45.encode(compress(encoded))
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
