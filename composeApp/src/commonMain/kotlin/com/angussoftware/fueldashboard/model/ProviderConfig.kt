package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies which provider adapter implementation to use.
 * Stored in settings as a discriminator.
 */
@Serializable
enum class ProviderKind(val displayName: String) {
    ZAI("z.ai"),
    LETTA_CLOUD("Letta Cloud"),
    OPENAI("OpenAI"),
}

/**
 * Configuration for a single provider instance.
 *
 * Each entry in the settings "providers" list is one of these.
 * Multiple providers can be configured simultaneously — the dashboard shows a section for each.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val kind: ProviderKind,
    val apiKey: String = "",
    val serverUrl: String = "",
    val displayName: String = "",
) {
    /**
     * Resolved display name: custom name > provider's default.
     */
    fun resolvedDisplayName(): String =
        displayName.ifBlank { "${kind.displayName}" }

    /**
     * Server URL or default per provider kind.
     */
    fun resolvedServerUrl(): String = when (kind) {
        ProviderKind.ZAI -> serverUrl.ifBlank { "https://api.z.ai" }
        ProviderKind.LETTA_CLOUD -> serverUrl.ifBlank { "https://api.letta.com" }
        ProviderKind.OPENAI -> serverUrl.ifBlank { "https://api.openai.com" }
    }

    /**
     * True if this config has enough info to poll.
     */
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

/**
 * Serializable settings wrapper for multi-provider storage.
 *
 * The providers list is serialized as JSON and stored in a single settings key.
 */
@Serializable
data class MultiProviderSettings(
    @SerialName("providers")
    val providers: List<ProviderConfig> = emptyList(),
    /** Whether the orchestrator (connected mode) is also enabled. */
    @SerialName("orchestrator_enabled")
    val orchestratorEnabled: Boolean = false,
    @SerialName("orchestrator_url")
    val orchestratorUrl: String = "http://127.0.0.1:8321",
) {
    val hasConfiguredProvider: Boolean get() = providers.any { it.isConfigured }
    val hasAnyConfig: Boolean get() = hasConfiguredProvider || (orchestratorEnabled && orchestratorUrl.isNotBlank())
}
