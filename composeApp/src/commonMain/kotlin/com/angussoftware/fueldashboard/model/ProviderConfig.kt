package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies which provider adapter implementation to use.
 * Stored in settings as a discriminator.
 */
@Serializable
enum class ProviderCategory(val label: String) {
    LLM_PROVIDER("LLM Providers"),
    AGENT_BACKEND("Agent Backend"),
}

enum class ProviderKind(val displayName: String, val category: ProviderCategory) {
    ZAI("z.ai", ProviderCategory.LLM_PROVIDER),
    LETTA_CLOUD("Letta Cloud", ProviderCategory.LLM_PROVIDER),
    OPENAI("OpenAI", ProviderCategory.LLM_PROVIDER),
    ANTHROPIC("Anthropic", ProviderCategory.LLM_PROVIDER),
    DEEPSEEK("DeepSeek", ProviderCategory.LLM_PROVIDER),
    GROQ("Groq", ProviderCategory.LLM_PROVIDER),
    MISTRAL("Mistral AI", ProviderCategory.LLM_PROVIDER),
    JUNIE("Junie", ProviderCategory.LLM_PROVIDER),
    CONNECTED_API("Remote Dashboard", ProviderCategory.AGENT_BACKEND),
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
    val monthlyBudgetUsd: Double = 0.0,
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
        ProviderKind.ANTHROPIC -> serverUrl.ifBlank { "https://api.anthropic.com" }
        ProviderKind.DEEPSEEK -> serverUrl.ifBlank { "https://api.deepseek.com" }
        ProviderKind.GROQ -> serverUrl.ifBlank { "https://api.groq.com/openai" }
        ProviderKind.MISTRAL -> serverUrl.ifBlank { "https://api.mistral.ai" }
        ProviderKind.JUNIE -> ""
        ProviderKind.CONNECTED_API -> serverUrl.ifBlank { "http://127.0.0.1:8321" }
    }

    /**
     * True if this config has enough info to poll.
     * Most providers need an API key. CONNECTED_API needs a server URL.
     */
    val isConfigured: Boolean
        get() = when (kind) {
            ProviderKind.CONNECTED_API -> resolvedServerUrl().isNotBlank()
            ProviderKind.JUNIE -> true
            else -> apiKey.isNotBlank()
        }
}

val ProviderKind.supportsMonthlyBudget: Boolean
    get() = this == ProviderKind.OPENAI || this == ProviderKind.ANTHROPIC || this == ProviderKind.MISTRAL

/**
 * Serializable settings wrapper for multi-provider storage.
 *
 * The providers list is serialized as JSON and stored in a single settings key.
 */
@Serializable
data class MultiProviderSettings(
    @SerialName("providers")
    val providers: List<ProviderConfig> = emptyList(),
) {
    val hasConfiguredProvider: Boolean get() = providers.any { it.isConfigured }
    val hasAnyConfig: Boolean get() = hasConfiguredProvider
}
