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
    OPENROUTER("OpenRouter", ProviderCategory.LLM_PROVIDER),
    GEMINI("Google Gemini", ProviderCategory.LLM_PROVIDER),
    XAI("xAI", ProviderCategory.LLM_PROVIDER),
    QWEN("Qwen (DashScope)", ProviderCategory.LLM_PROVIDER),
    TOGETHER("Together AI", ProviderCategory.LLM_PROVIDER),
    JUNIE("Junie", ProviderCategory.LLM_PROVIDER),
    CLAUDE_CODE("Claude Code (plan)", ProviderCategory.LLM_PROVIDER),
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
    /** How often this provider is polled, in seconds. Default 60. */
    val pollIntervalSeconds: Int = 60,
    /**
     * Display-only: no local polling. Set when this provider was imported
     * via settings sync alongside a Remote Dashboard (CONNECTED_API)
     * provider — the phone would otherwise re-poll every account the
     * desktop already polls 24/7, doubling quota burn against the same
     * accounts. Tiles hydrate from the remote /dashboard snapshot instead.
     * Cleared when the Remote Dashboard is removed or the provider is
     * edited (explicit user intent to poll locally). Defaults false and is
     * omitted from QR sync payloads when false (encodeDefaults=false).
     */
    val dormant: Boolean = false,
    /**
     * Opt-in: the command that makes THIS provider the active one.
     *
     * Direction matters and is the opposite of what it looks like at first
     * glance. This is not "what to run when I run dry" — it is "how to switch
     * TO me". That reading is what lets one field serve both callers:
     *
     *  - the manual button on this provider's card means "swap to this one",
     *    which is simply running this command;
     *  - the automatic trigger, when some OTHER provider runs dry, runs the
     *    command belonging to whichever provider it is moving to.
     *
     * It also matches how such tools are actually invoked — the target is the
     * argument (`my-switch backup`, `my-switch primary`), so the command and
     * the provider it names are naturally paired.
     *
     * Blank (the default) disables it entirely, so no existing install gains
     * behaviour by upgrading. This is the only setting here that can change
     * the state of the machine, so it is never enabled by default, never
     * inferred, and the outcome of every run is recorded in the decision log.
     *
     * Split on whitespace and executed directly, with no shell, so
     * `my-script --provider backup` works while pipes, redirects and globs do
     * not. Point it at a script if you need those. Desktop only.
     */
    val activateCommand: String = "",
    /**
     * Remaining-percent threshold at which work should move OFF this provider.
     * 0 disables.
     *
     * Note the asymmetry with [activateCommand], which is deliberate: this
     * says when to leave, that says how to arrive. When this provider falls
     * below the threshold the trigger runs a *different* provider's
     * [activateCommand] — the healthiest one that reports a known level and
     * has more left than this one. A provider whose level is unknown is never
     * a candidate, so an unreadable gauge cannot be mistaken for a full tank.
     *
     * Guarded by agreement across polls, a fire-once-per-excursion rule, a
     * cooldown and the fleet-idle gate — see
     * [com.angussoftware.fueldashboard.engine.SwitchCommandTrigger].
     */
    val swapAwayBelowPct: Int = 0,
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
        ProviderKind.OPENROUTER -> serverUrl.ifBlank { "https://openrouter.ai/api" }
        ProviderKind.GEMINI -> serverUrl.ifBlank { "https://generativelanguage.googleapis.com" }
        ProviderKind.XAI -> serverUrl.ifBlank { "https://api.x.ai" }
        ProviderKind.QWEN -> serverUrl.ifBlank { "https://dashscope.aliyuncs.com/api" }
        ProviderKind.TOGETHER -> serverUrl.ifBlank { "https://api.together.xyz" }
        ProviderKind.JUNIE -> ""
        ProviderKind.CLAUDE_CODE -> serverUrl.ifBlank { "https://api.anthropic.com" }
        ProviderKind.CONNECTED_API -> serverUrl.ifBlank { "http://127.0.0.1:8322" }
    }

    /**
     * True if this config has enough info to poll.
     * Most providers need an API key. CONNECTED_API needs a server URL.
     */
    val isConfigured: Boolean
        get() = when (kind) {
            ProviderKind.CONNECTED_API -> resolvedServerUrl().isNotBlank()
            ProviderKind.JUNIE -> true
            // Authenticates with the OAuth token Claude Code already stores
            // locally — there is no key to paste, so adding it is enough.
            ProviderKind.CLAUDE_CODE -> true
            else -> apiKey.isNotBlank()
        }
}

val ProviderKind.supportsMonthlyBudget: Boolean
    get() = this == ProviderKind.OPENAI || this == ProviderKind.ANTHROPIC || this == ProviderKind.MISTRAL ||
        this == ProviderKind.OPENROUTER || this == ProviderKind.QWEN || this == ProviderKind.TOGETHER

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
