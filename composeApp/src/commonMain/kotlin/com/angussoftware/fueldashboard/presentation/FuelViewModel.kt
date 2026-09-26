package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.engine.Complexity
import com.angussoftware.fueldashboard.engine.FuelConfig
import com.angussoftware.fueldashboard.engine.FuelModel
import com.angussoftware.fueldashboard.engine.FuelProviderConfig
import com.angussoftware.fueldashboard.engine.ProviderStateInfo
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ClaudeCodeFleet
import com.angussoftware.fueldashboard.model.ClaudeCodeRoute
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.network.ClaudeCodeUsageHttpException
import com.angussoftware.fueldashboard.model.readClaudeCodeFleet
import com.angussoftware.fueldashboard.model.readClaudeCodeRoute
import com.angussoftware.fueldashboard.settings.SecretRef
import com.angussoftware.fueldashboard.settings.resolveSecretRef
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.network.AnthropicProviderAdapter
import com.angussoftware.fueldashboard.engine.SwitchCommandResult
import com.angussoftware.fueldashboard.engine.SwapTarget
import com.angussoftware.fueldashboard.engine.SwitchCommandTrigger
import com.angussoftware.fueldashboard.engine.runSwitchCommand
import com.angussoftware.fueldashboard.engine.switchCommandsSupported
import com.angussoftware.fueldashboard.network.ClaudeCodeSubscriptionAdapter
import com.angussoftware.fueldashboard.network.ConnectedApiProviderAdapter
import com.angussoftware.fueldashboard.network.DeepSeekProviderAdapter
import com.angussoftware.fueldashboard.network.GeminiProviderAdapter
import com.angussoftware.fueldashboard.network.GroqProviderAdapter
import com.angussoftware.fueldashboard.network.JunieProviderAdapter
import com.angussoftware.fueldashboard.network.LettaCloudProviderAdapter
import com.angussoftware.fueldashboard.network.MistralProviderAdapter
import com.angussoftware.fueldashboard.network.OpenRouterProviderAdapter
import com.angussoftware.fueldashboard.network.QwenProviderAdapter
import com.angussoftware.fueldashboard.network.TogetherProviderAdapter
import com.angussoftware.fueldashboard.network.XaiProviderAdapter
import com.angussoftware.fueldashboard.network.OpenAIProviderAdapter
import com.angussoftware.fueldashboard.network.ZaiProviderAdapter
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import com.angussoftware.fueldashboard.usage.IngestionStatus
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * State for the multi-provider dashboard.
 */
/**
 * Real fuel projection computed from actual gauge data.
 * No fake recommendations — just honest math.
 */
data class FuelProjection(
    val currentPct: Double,
    val burnRatePerHr: Double?,
    val hoursUntilReset: Double,
    val hoursUntilExhaustion: Double?,
    val projectedRemainingAtReset: Double,
    val willMakeIt: Boolean,
    val headroomPct: Double,
    val activeAgentCount: Int,
    val activeModels: List<String>,
)

data class ModelDrainRateDisplay(
    val model: String,
    val totalFuelConsumed: Double,
    val sampleCount: Int,
    val avgDrainPerHr: Double,
)

data class ProviderSnapshotInput(
    val providerId: String,
    val providerName: String,
    val providerType: String,
    val remainingPct: Double?,
    val resetAt: Long?,
    val windowHours: Double?,
    val quotaType: QuotaType = QuotaType.RATE_WINDOW,
)

/** Metered token usage for one source (agent/runtime/tool) or model. */
data class MeteredUsageDisplay(
    val label: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
    /** Credit cost using z.ai GLM Coding Plan multipliers, if known. */
    val creditCost: Double? = null,
)

/** Metered usage for one conversation — includes agent + model for context. */
data class ConversationUsageDisplay(
    val conversationId: String,
    val agentName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
    /** Credit cost using z.ai GLM Coding Plan multipliers, if known. */
    val creditCost: Double? = null,
    /** Human-readable conversation title, if known (falls back to shortened ID). */
    val title: String? = null,
)

/** Metered usage for one agent × model combination — the cross-tab cell. */
data class AgentModelUsageDisplay(
    val agentName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
    /** Credit cost using z.ai GLM Coding Plan multipliers, if known. */
    val creditCost: Double? = null,
)

/** Aggregated metered usage over two display windows. */
data class MeteredUsageWindows(
    val bySource24h: List<MeteredUsageDisplay>,
    val byModel24h: List<MeteredUsageDisplay>,
    val bySource7d: List<MeteredUsageDisplay>,
    val byModel7d: List<MeteredUsageDisplay>,
    val byConversation24h: List<ConversationUsageDisplay> = emptyList(),
    val byConversation7d: List<ConversationUsageDisplay> = emptyList(),
    val byAgentModel24h: List<AgentModelUsageDisplay> = emptyList(),
    val byAgentModel7d: List<AgentModelUsageDisplay> = emptyList(),
)

/** Fuel Intelligence tab data — waste windows + merged event timeline. */
data class IntelligenceData(
    val wasteByProvider: List<FuelIntelligence.ProviderWaste> = emptyList(),
    val fuelEvents: List<FuelIntelligence.FuelEvent> = emptyList(),
    val advice: FuelAdvisor.Advice? = null,
)

/**
 * z.ai GLM Coding Plan credit multipliers.
 * Credits = (input×inputMult + cachedIn×cachedMult + output×outputMult) / 10,000
 * — the /10,000 divisor is part of the official formula (docs.z.ai
 * /devpack/teamplan "Credit Calculation"; plan scale sanity: Team Standard
 * = 15,000 credits / 5h). Cached input is charged at the cached rate; we
 * conservatively use the full input rate until metered data distinguishes
 * cached tokens. Requests for GLM-5.2/5.1 auto-route to 5.3.
 * Off-peak (Mon–Fri 14:00–18:00 UTC+8) charges 50%; blended average used.
 */
object ZaiCreditMultipliers {
    private data class Mult(val input: Double, val cached: Double, val output: Double)

    private val models = mapOf(
        "glm-5.3" to Mult(6.9, 1.7, 24.0),
        "glm-5.2" to Mult(6.9, 1.7, 24.0), // auto-routed to 5.3
        "glm-5.1" to Mult(6.9, 1.7, 24.0), // auto-routed to 5.3
        "glm-5-turbo" to Mult(5.7, 1.5, 21.0),
        "glm-4.7" to Mult(4.6, 1.2, 16.0),
    )

    fun known(model: String): Boolean = normalize(model) in models

    /** Cheapest known model (for routine-work downgrade projections). */
    fun cheapestKnown(): String? =
        models.entries.minByOrNull { it.value.input }?.key

    fun normalize(model: String): String = model.trim().lowercase()

    fun cost(model: String, inputTokens: Long, outputTokens: Long): Double? {
        val m = models[normalize(model)] ?: return null
        return (inputTokens * m.input + outputTokens * m.output) / 10_000.0
    }
}

/** Credit cost for metered tokens on the z.ai plan; null when model unknown. */
fun zaiCreditCost(model: String, inputTokens: Long, outputTokens: Long): Double? =
    ZaiCreditMultipliers.cost(model, inputTokens, outputTokens)

/**
 * Models whose metered usage carries no credit cost although they look like
 * z.ai GLM-family models. Multipliers come from z.ai's published docs (no
 * discovery API), so the table is hand-maintained — this is the drift
 * signal that says when it needs an update, instead of silently
 * under-reporting credits.
 *
 * Non-GLM models (claude-*, gpt-*, local handles reported via report_usage)
 * are out of the table's scope by design, not drift — they are excluded so
 * the warning only fires when a plausible z.ai model is actually missing.
 */
fun unknownCostModels(rows: List<MeteredUsageDisplay>): List<String> =
    rows.filter { it.creditCost == null && it.inputTokens + it.outputTokens > 0 }
        .map { it.label.trim() }
        .filter { it.startsWith("glm", ignoreCase = true) }
        .distinct()

/**
 * Whether a metered-usage row should show the "cr ?" marker (known-missing
 * cost for a GLM-family model). Same scope rule as [unknownCostModels]:
 * non-GLM handles are out of the cost table's scope, not drift.
 */
fun shouldShowCostUnknown(label: String, creditCost: Double?, inputTokens: Long, outputTokens: Long): Boolean =
    creditCost == null && inputTokens + outputTokens > 0 &&
        label.trim().startsWith("glm", ignoreCase = true)

/**
 * Classifies how a provider's quota works — determines UI treatment.
 * - RATE_WINDOW: Self-healing. Hitting 0 means throttling, not running out.
 *   Examples: z.ai 5h TOKENS_PCT, Letta daily, Letta 4hr.
 * - CREDIT_POOL: Finite budget that depletes. Refills on schedule.
 *   Examples: Letta monthly credits, Junie balance.
 * - SPEND_ONLY: Finite budget, no automatic refill.
 *   Examples: OpenRouter, out-of-pocket API keys.
 */
enum class QuotaType {
    RATE_WINDOW,
    CREDIT_POOL,
    SPEND_ONLY,
}

data class ProviderBurnRateDisplay(
    val providerId: String,
    val providerName: String,
    val currentPct: Double?,
    val burnRatePerHr: Double?,
    val hoursUntilReset: Double,
    val hoursUntilExhaustion: Double?,
    val projectedRemainingAtReset: Double,
    val willMakeIt: Boolean,
    val history: List<Double>,
    val quotaType: QuotaType = QuotaType.RATE_WINDOW,
    val windowHours: Double = 0.0,
)

/**
 * Outcome of a manual switch-command run, shown inline under the button that
 * started it. Distinct from [DashboardState.providerErrors]: that is "polling
 * this provider failed", this is "the swap you asked for did/did not happen".
 */
data class SwitchRunStatus(
    val ok: Boolean,
    val message: String,
    /**
     * True when this was the fleet gate refusing, and only then.
     *
     * The operator may legitimately know something the gate cannot: most
     * often that the "busy" session is the very one being used to ask for the
     * swap, in which case waiting for idle waits forever. Surfacing an
     * override only after a refusal keeps it a deliberate second step rather
     * than a checkbox someone silences once and forgets.
     */
    val overridable: Boolean = false,
)

data class DashboardState(
    val settings: MultiProviderSettings = MultiProviderSettings(),
    val providerReports: Map<String, ProviderReport> = emptyMap(),
    val providerErrors: Map<String, String> = emptyMap(),
    val fuel: FuelResponse? = null,
    val decisions: DecisionsResponse = DecisionsResponse(),
    val agents: AgentsResponse = AgentsResponse(),
    val alerts: AlertsResponse = AlertsResponse(),
    val isLoading: Boolean = true,
    val lastUpdated: Long = 0L,
    val burnRate: Double? = null,
    val dataPointCount: Int = 0,
    val acpAgents: List<AcpAgentDisplay> = emptyList(),
    val agentSettings: AgentSettings = AgentSettings(),
    val serverUrl: String? = null,
    val serverApiKey: String? = null,
    val junieBalance: Double? = null,
    val junieLicense: String? = null,
    val junieLastChecked: Long? = null,
    val showHelp: Boolean = true,
    val showThemeIcon: Boolean = true,
    val showAdvisor: Boolean = false, // advisor hidden by default (Harry, Aug 24)
    val checkingProviderIds: Set<String> = emptySet(),
    /**
     * Providers parked after a server asked us to back off, by the epoch
     * millisecond they may be polled again.
     *
     * Surfaced rather than kept private because a silently parked provider and
     * a broken one look identical from the outside: the tile simply stops
     * changing. That ambiguity is the whole reason this is in state.
     */
    val rateLimitedUntil: Map<String, Long> = emptyMap(),
    /**
     * Providers that answered with nothing, but not yet often enough to call
     * it unavailable. The tile shows a spinner for these rather than an
     * alarming badge — see [consecutiveUnavailable].
     */
    val settlingProviderIds: Set<String> = emptySet(),
    /** Providers whose switch command is running right now. */
    val swappingProviderIds: Set<String> = emptySet(),
    /** Result of the last manual swap per provider, until the next poll clears it. */
    val switchResults: Map<String, SwitchRunStatus> = emptyMap(),
    val fuelProjection: FuelProjection? = null,
    val modelDrainRates: List<ModelDrainRateDisplay> = emptyList(),
    val fuelHistory: List<Double> = emptyList(),
    val providerBurnRates: List<ProviderBurnRateDisplay> = emptyList(),
    val usageIngestion: IngestionStatus = IngestionStatus(),
    val meteredBySource24h: List<MeteredUsageDisplay> = emptyList(),
    val meteredByModel24h: List<MeteredUsageDisplay> = emptyList(),
    val meteredBySource7d: List<MeteredUsageDisplay> = emptyList(),
    val meteredByModel7d: List<MeteredUsageDisplay> = emptyList(),
    val meteredByConversation24h: List<ConversationUsageDisplay> = emptyList(),
    val meteredByConversation7d: List<ConversationUsageDisplay> = emptyList(),
    val meteredByAgentModel24h: List<AgentModelUsageDisplay> = emptyList(),
    val meteredByAgentModel7d: List<AgentModelUsageDisplay> = emptyList(),
    val wasteByProvider: List<FuelIntelligence.ProviderWaste> = emptyList(),
    val fuelEvents: List<FuelIntelligence.FuelEvent> = emptyList(),
    val fuelAdvice: FuelAdvisor.Advice? = null,
    /**
     * Where Claude Code is currently routed, or null when it could not be
     * read. Null is NOT the same as [ClaudeCodeRoute.isDefaultAnthropic]:
     * one means "we could not tell", the other means "stock Anthropic".
     */
    val claudeCodeRoute: ClaudeCodeRoute? = null,
    /** Live Claude Code sessions by status, or null when unreadable. */
    val claudeCodeFleet: ClaudeCodeFleet? = null,
) {
    /** All configured providers (have enough info to poll), in user order. */
    val activeProviders: List<ProviderConfig>
        get() = settings.providers.filter { it.isConfigured }

    /** Whether any connected API (orchestrator) is active — used for agents/alerts panel visibility. */
    val hasConnectedApi: Boolean
        get() = settings.providers.any { it.kind == ProviderKind.CONNECTED_API && it.isConfigured }
}


class FuelViewModel(
    /**
     * Overridden in tests; production reads this machine's Claude Code session
     * registry.
     *
     * Injected because [readClaudeCodeFleet] is a top-level expect function,
     * which left the fleet gate — the only thing standing between a switch
     * command and a live agent turn — impossible to exercise in a test.
     */
    private val fleetReader: () -> ClaudeCodeFleet? = { readClaudeCodeFleet() },
    /**
     * Re-read after a swap to check whether it actually took effect. Injected
     * for the same reason as [fleetReader]: the underlying reader is a
     * top-level expect fun and this is the only way to test the verification.
     */
    private val routeReader: () -> ClaudeCodeRoute? = { readClaudeCodeRoute() },
    /** Overridden in tests; production spawns the real process. */
    private val switchRunner: suspend (String) -> SwitchCommandResult = { runSwitchCommand(it) },
) {

    companion object {
        /**
         * Empty readings in a row before a tile stops saying "connecting" and
         * starts saying UNAVAILABLE. Two, for the same reason the switch
         * trigger wants two: one reading is a moment, two is a state.
         */
        internal const val UNAVAILABLE_STREAK = 2

        /**
         * Process-wide shared instance: the Android foreground notification
         * service and the Activity (and any desktop windows) share ONE
         * ViewModel so there is exactly one polling loop and one adapter set.
         */
        val shared: FuelViewModel by lazy { FuelViewModel() }
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val adapters = mutableMapOf<String, ProviderAdapter>()

    /**
     * Credentials that a [SecretRef] could not resolve, by provider id.
     *
     * Kept so refresh can surface "your vault is locked" on the tile instead
     * of letting the provider poll with no key and report an opaque 401.
     *
     * Declared HERE, beside [adapters], because the constructor calls
     * activateAdapters -> createAdapter, which writes to this map. A property
     * declared further down the class body is still null at that point, and
     * the app died on startup with a NullPointerException that neither the
     * compiler nor the test suite caught.
     */
    private val secretResolutionErrors = mutableMapOf<String, String>()

    /**
     * Serializes refresh cycles. The poll timer, manual refreshNow(), and
     * settings-change re-activations can all trigger refresh concurrently;
     * unserialized they interleave state writes, double-log provider
     * snapshots, and race _lastRecommendation. A mutex (rather than
     * tryLock-skip) so a manual refresh during a slow poll waits and runs
     * fresh data next, rather than silently doing nothing.
     */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    // ── Agent merge: three independent sources, single merged output ─────
    // Each source updates its own list and calls mergeAcpAgents() which
    // combines all three (dedup by id/name) and writes to state.acpAgents.
    // This replaces the old pattern where each writer replaced acpAgents
    // wholesale, causing agents to flicker out for up to 5s.
    private val acpDiscoveredAgents = mutableListOf<AcpAgentDisplay>()
    private val registeredAgents = mutableListOf<AcpAgentDisplay>()
    private val orchestratorAgents = mutableListOf<AcpAgentDisplay>()

    private fun mergeAcpAgents() {
        val merged = mutableListOf<AcpAgentDisplay>()
        // ACP-discovered agents first (they have the richest data)
        merged.addAll(acpDiscoveredAgents)
        // Add orchestrator agents not already present
        for (agent in orchestratorAgents) {
            if (merged.none { it.id == agent.id || it.name.equals(agent.name, ignoreCase = true) }) {
                merged.add(agent)
            }
        }
        // Add registered (MCP/HTTP) agents not already present
        for (agent in registeredAgents) {
            if (merged.none { it.id == agent.id || it.name.equals(agent.name, ignoreCase = true) }) {
                merged.add(agent)
            }
        }
        // Also preserve any config-only (synced) agents from agentSettings
        for (agent in _state.value.acpAgents) {
            if (agent.status == "synced" && merged.none { it.id == agent.id }) {
                merged.add(agent)
            }
        }
        _state.update { it.copy(acpAgents = merged) }
    }

    /** Tracks last recommendation to avoid duplicate logging. */
    private var _lastRecommendation: String = ""

    /** Live usage-ingestion status pushed from the desktop ingestion manager. */
    fun updateUsageIngestion(status: IngestionStatus) {
        _state.update { it.copy(usageIngestion = status) }
    }

    private val _state = MutableStateFlow(
        DashboardState(
            isLoading = false,
            showHelp = loadStringSetting(FuelSettingsKeys.SHOW_HELP, "true").toBoolean(),
            showThemeIcon = loadStringSetting(FuelSettingsKeys.SHOW_THEME_ICON, "true").toBoolean(),
            showAdvisor = loadStringSetting(FuelSettingsKeys.SHOW_ADVISOR, "false").toBoolean(),
        ),
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        val settings = FuelSettingsStore.loadMultiProvider()
        val agents = AgentSettingsStore.load()
        val serverKey = ServerApiKeyStore.load()
        _state.update { it.copy(
            settings = settings,
            agentSettings = agents,
            serverApiKey = serverKey.ifBlank { null },
            junieBalance = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull(),
            junieLicense = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null },
            junieLastChecked = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull(),
        ) }
        // Materialize config-only agent display entries from stored settings
        // so synced agents survive a restart (mobile has no live ACP to
        // re-discover them).
        materializeConfigAgents()
        if (settings.hasAnyConfig) {
            activateAdapters(settings)
        }
    }

    /**
     * Derives display entries from stored [AgentSettings] and merges them
     * with any existing live/remote agents in [acpAgents]. Config-only
     * entries (status="synced") are added for any agent ID not already
     * present in the live list. This is called at init and after every
     * settings import so agents don't vanish on restart.
     */
    private fun materializeConfigAgents() {
        val configAgents = _state.value.agentSettings.agents
        if (configAgents.isEmpty()) return
        val liveAgentIds = _state.value.acpAgents.map { it.id }.toSet()
        val configOnly = configAgents.filter { it.id !in liveAgentIds }.map { config ->
            AcpAgentDisplay(
                id = config.id,
                name = config.name,
                currentModel = "unknown",
                availableModels = emptyList(),
                currentMode = null,
                availableModes = emptyList(),
                status = "synced",
                capabilities = emptyList(),
                lastSeen = null,
            )
        }
        if (configOnly.isNotEmpty()) {
            // Add config-only entries to state first, then merge all sources.
            // mergeAcpAgents() preserves status="synced" entries.
            _state.update { state ->
                state.copy(acpAgents = state.acpAgents + configOnly)
            }
            mergeAcpAgents()
        }
    }

    fun getServerApiKey(): String = ServerApiKeyStore.load()
    fun getJunieBalance(): Double? = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull()
    fun getJunieLicense(): String? = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null }
    fun getJunieLastChecked(): Long? = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull()

    fun startPolling() {
        if (pollJob?.isActive == true) return
        if (!_state.value.settings.hasAnyConfig) return

        pollJob = scope.launch {
            refresh()
            val interval = pollIntervalMs()
            delay(interval)
            while (true) {
                try {
                    refresh()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e // scope shutdown — never swallow cooperative cancellation
                } catch (e: Exception) {
                    // Don't let one failed refresh kill the poll loop.
                    // Log the error and continue after the interval.
                    _state.update {
                        it.copy(
                            isLoading = false,
                            providerErrors = it.providerErrors + ("global" to (e.message ?: "Poll error")),
                        )
                    }
                }
                delay(interval)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * One-shot refresh for background workers (Android WorkManager): runs a
     * single refresh cycle under the same mutex as the poll loop and returns.
     * Unlike [startPolling], it does not schedule any follow-up work.
     */
    suspend fun pollOnce() {
        refresh()
    }

    fun refreshNow() {
        scope.launch { refresh() }
    }

    // --- ACP agent state (set from desktop main.kt) ---

    /**
     * Callbacks for ACP agent model/mode changes. Set from main.kt where the
     * AcpAgentManager lives. Null on platforms without ACP support (Android).
     */
    /**
     * Callback invoked when the user clicks delete on an agent card.
     * Set from main.kt (desktop) to remove from EmbeddedServer registry.
     * Null on platforms without the embedded server (Android).
     */
    var onRemoveAgent: ((agentId: String) -> Unit)? = null


    /**
     * Callback invoked when agent settings change (add/remove).
     * Set from main.kt (desktop) to restart AcpAgentManager with new config.
     * Null on platforms without ACP support (Android).
     */
    var onAgentSettingsChanged: ((AgentSettings) -> Unit)? = null

    /**
     * Callback invoked when the decision engine picks a model.
     * Set from main.kt (desktop) to persist to SQLite via DecisionRepository.
     * Null on platforms without DB support.
     */
    var onDecisionLogged: ((
        agentId: String,
        modelHandle: String,
        provider: String,
        tier: String,
        complexity: String,
        utilizationRatio: Double,
        headroom: Int,
        reason: String,
    ) -> Unit)? = null

    /**
     * Callback to fetch recent decisions from local storage (desktop only).
     * Called after each poll to populate the DecisionLog.
     */
    var onFetchDecisions: (() -> List<Decision>)? = null

    /**
     * Callback to log a fuel snapshot to SQLite (desktop only).
     * Called after each poll to record real fuel state for burn-rate analysis.
     */
    var onLogFuelSnapshot: ((
        tokensPct: Double?,
        sessionPct: Double?,
        activeAgentCount: Int,
        activeModels: String?,
        resetAt: Long?,
    ) -> Unit)? = null

    /**
     * Callback to compute burn rate from stored snapshots (desktop only).
     * Returns % per hour, or null if insufficient data.
     */
    var onComputeBurnRate: (() -> Double?)? = null

    /**
     * Callback to fetch recent fuel snapshots for projection computation.
     */
    var onGetProjection: ((currentPct: Double, resetAt: Long?, burnRate: Double) -> FuelProjection?)? = null

    /**
     * Callback to fetch per-model drain rates (desktop only).
     * Returns measured fuel consumption attributed to each model.
     */
    var onGetModelDrainRates: (() -> List<ModelDrainRateDisplay>)? = null

    /** Fuel Intelligence data (waste windows + event timeline), or null when unavailable. */
    var onGetIntelligence: (() -> IntelligenceData?)? = null

    /** Metered usage aggregates (from usage_records), 24h and 7d windows. */
    var onGetMeteredUsage: (() -> MeteredUsageWindows?)? = null

    /**
     * Callback to fetch recent fuel percentages for sparkline chart.
     */
    var onGetFuelHistory: (() -> List<Double>)? = null

    /**
     * Callback to log all provider fuel states per poll cycle.
     */
    var onLogProviderSnapshots: ((List<ProviderSnapshotInput>) -> Unit)? = null

    /**
     * Switch-command trigger state per provider id, carried across polls.
     *
     * Deliberately in memory: persisting it would let a restart resume a
     * half-built agreement streak, and the trigger is safer starting from a
     * clean slate than from a partially-counted descent.
     */
    // ConcurrentHashMap: written from the refresh coroutine and the manual
    // switch scope.launch (both Dispatchers.IO) — a plain map can lose writes
    // or corrupt under concurrent access.
    private val switchTriggerState = java.util.concurrent.ConcurrentHashMap<String, SwitchCommandTrigger.State>()

    /**
     * Callback to get per-provider burn rates and projections.
     */
    var onGetProviderBurnRates: (() -> List<ProviderBurnRateDisplay>)? = null

    /**
     * Push ACP-discovered agent display data. Called from main.kt
     * when the AcpAgentManager StateFlow emits updates.
     */
    fun updateAcpAgents(agents: List<AcpAgentDisplay>) {
        acpDiscoveredAgents.clear()
        acpDiscoveredAgents.addAll(agents)
        mergeAcpAgents()
    }

    /**
     * Push MCP/HTTP-registered agent display data. Called from main.kt
     * poll loop every 5s.
     */
    fun updateRegisteredAgents(agents: List<AcpAgentDisplay>) {
        registeredAgents.clear()
        registeredAgents.addAll(agents)
        mergeAcpAgents()
    }

    fun setServerUrl(url: String?) {
        _state.update { it.copy(serverUrl = url) }
    }

    fun setShowHelp(showHelp: Boolean) {
        saveStringSetting(FuelSettingsKeys.SHOW_HELP, showHelp.toString())
        _state.update { it.copy(showHelp = showHelp) }
    }

    fun setShowThemeIcon(showThemeIcon: Boolean) {
        saveStringSetting(FuelSettingsKeys.SHOW_THEME_ICON, showThemeIcon.toString())
        _state.update { it.copy(showThemeIcon = showThemeIcon) }
    }

    fun setShowAdvisor(showAdvisor: Boolean) {
        saveStringSetting(FuelSettingsKeys.SHOW_ADVISOR, showAdvisor.toString())
        _state.update { it.copy(showAdvisor = showAdvisor) }
    }

    /** Runs Junie's chargeable balance command only after an explicit desktop user action. */
    fun checkJunieCredits(providerId: String) {
        val adapter = adapters[providerId] as? JunieProviderAdapter ?: return
        if (providerId in _state.value.checkingProviderIds) return

        _state.update { it.copy(
            checkingProviderIds = it.checkingProviderIds + providerId,
        ) }
        scope.launch {
            runCatching { adapter.checkBalance() }
                .onSuccess { report ->
                    _state.update { it.copy(
                        providerReports = it.providerReports + (providerId to report),
                        providerErrors = it.providerErrors - providerId,
                        checkingProviderIds = it.checkingProviderIds - providerId,
                        lastUpdated = epochMillis(),
                    ) }
                }
                .onFailure { error ->
                    _state.update { it.copy(
                        providerErrors = it.providerErrors + (providerId to (error.message ?: "Junie balance check failed")),
                        checkingProviderIds = it.checkingProviderIds - providerId,
                    ) }
                }
        }
    }

    /**
     * Runs a provider's switch command right now, on an explicit click.
     *
     * The trigger's four guards — threshold, two-poll agreement,
     * fire-once-per-excursion and cooldown — all exist to make an *automatic*
     * decision trustworthy with no human in the loop. A click is that human, so
     * this bypasses them: you can swap off a provider that is nowhere near its
     * threshold, and twice in a row if you mean it.
     *
     * The fleet gate is not one of those guards and is NOT bypassed. It guards
     * somebody else's in-flight turn, which your click does not make safe.
     *
     * On a run that actually happened the cooldown is armed, so the automatic
     * trigger cannot fire again on the very next poll behind your back.
     */
    fun runSwitchCommandNow(
        providerId: String,
        /**
         * Proceed even though sessions are still working.
         *
         * Reachable only from the refusal the operator has already seen, so
         * this cannot be the first thing a click does. The automatic trigger
         * never passes it.
         */
        force: Boolean = false,
    ) {
        if (!switchCommandsSupported) return
        val config = _state.value.settings.providers.firstOrNull { it.id == providerId } ?: return
        if (config.activateCommand.isBlank()) return
        if (providerId in _state.value.swappingProviderIds) return

        _state.update { it.copy(
            swappingProviderIds = it.swappingProviderIds + providerId,
            switchResults = it.switchResults - providerId,
        ) }
        scope.launch {
            val remaining = _state.value.providerReports[providerId]
                ?.takeIf { it.available }?.remainingPct
            val status = runCatching {
                val label = if (force) "manual swap (fleet gate overridden)" else "manual swap"
                when (val run = executeSwitchCommand(config, remaining, label, force = force)) {
                    is SwitchRun.Refused -> {
                        // A denied user action belongs in the log as much as a
                        // taken one: otherwise "I pressed it and nothing
                        // happened" leaves no trace to look up later.
                        onDecisionLogged?.invoke(
                            "switch-command",
                            config.activateCommand.take(120),
                            config.id,
                            "action",
                            "refused",
                            (remaining ?: 0) / 100.0,
                            remaining ?: 0,
                            "manual swap refused — fleet ${run.fleet?.describe() ?: "unreadable"}",
                        )
                        SwitchRunStatus(
                            ok = false,
                            message = refusalMessage(run.fleet),
                            overridable = true,
                        )
                    }
                    is SwitchRun.Ran -> {
                        switchTriggerState[providerId] =
                            (switchTriggerState[providerId] ?: SwitchCommandTrigger.State())
                                .copy(armed = false, lastFiredAt = epochMillis())
                        verifiedStatus(config, run.result)
                    }
                }
            }.getOrElse {
                SwitchRunStatus(
                    ok = false,
                    message = "Switch failed — ${it.message ?: it::class.simpleName}",
                )
            }

            _state.update { it.copy(
                swappingProviderIds = it.swappingProviderIds - providerId,
                switchResults = it.switchResults + (providerId to status),
            ) }
        }
    }

    // --- Settings updates ---

    /**
     * Updates multi-provider settings and restarts polling with the new adapters.
     */
    fun updateSettings(newSettings: MultiProviderSettings) {
        FuelSettingsStore.saveMultiProvider(newSettings)

        applySettings(newSettings)
    }

    /**
     * Reloads provider settings persisted by an external integration such as MCP.
     */
    fun reloadSettings() {
        applySettings(FuelSettingsStore.loadMultiProvider())
    }

    private fun applySettings(newSettings: MultiProviderSettings) {
        stopPolling()
        closeAdapters()
        // Invalidate any in-flight refresh built on the old configuration
        // (see configGeneration) BEFORE mutating adapter state.
        configGeneration.incrementAndGet()
        // Reset per-provider poll scheduling: the immediate post-save refresh
        // must treat every provider as due, or tiles stay blank until each
        // provider's interval elapses (review 1815). Failure backoff resets
        // too — a settings save is an explicit user retry.
        lastPolledMs.clear()
        consecutiveFailures.clear()

        // Wake dormant providers when no Remote Dashboard remains: dormant
        // only makes sense alongside a CONNECTED_API source. Removing the
        // Remote Dashboard (or any settings path that drops it) restores
        // direct polling so tiles don't sit display-only with no source.
        val settings = if (
            newSettings.providers.any { it.kind == ProviderKind.CONNECTED_API && it.isConfigured }
        ) {
            newSettings
        } else {
            newSettings.copy(providers = newSettings.providers.map { it.copy(dormant = false) })
        }

        _state.update { it.copy(
            settings = settings,
            isLoading = true,
            providerReports = emptyMap(),
            providerErrors = emptyMap(),
            fuel = null,
            decisions = DecisionsResponse(),
            agents = AgentsResponse(),
            alerts = AlertsResponse(),
            burnRate = null,
            dataPointCount = 0,
            checkingProviderIds = emptySet(),
            swappingProviderIds = emptySet(),
            switchResults = emptyMap(),
            settlingProviderIds = emptySet(),
        ) }

        if (settings.hasAnyConfig) {
            activateAdapters(settings)
            startPolling()
        }
    }

    /**
     * Adds a new provider to settings.
     */
    fun addProvider(
        kind: ProviderKind,
        apiKey: String,
        displayName: String = "",
        serverUrl: String = "",
        monthlyBudgetUsd: Double = 0.0,
    ) {
        val current = _state.value.settings
        val newProvider = ProviderConfig(
            id = FuelSettingsStore.generateProviderId(),
            kind = kind,
            apiKey = apiKey,
            displayName = displayName,
            serverUrl = serverUrl,
            monthlyBudgetUsd = monthlyBudgetUsd,
        )
        updateSettings(current.copy(providers = current.providers + newProvider))
    }

    /**
     * Removes a provider from settings.
     */
    fun removeProvider(providerId: String) {
        val current = _state.value.settings
        updateSettings(current.copy(providers = current.providers.filterNot { it.id == providerId }))
    }

    /**
     * Updates a single provider's configuration.
     */
    fun updateProvider(updated: ProviderConfig) {
        val current = _state.value.settings
        updateSettings(
            current.copy(
                // Editing a dormant provider is explicit intent to manage it
                // locally — wake it (the Remote Dashboard keeps its own
                // copy either way).
                providers = current.providers.map { if (it.id == updated.id) updated.copy(dormant = false) else it },
            ),
        )
    }

    /**
     * Moves a provider up (-1) or down (+1) in the user-ordered list.
     * Order flows everywhere: Overview sections, HUD, notification rows, sync.
     * Lightweight — reports are keyed by id, so no adapter restart is needed.
     */
    fun moveProvider(providerId: String, offset: Int) {
        val providers = _state.value.settings.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == providerId }
        if (index < 0) return
        val target = index + offset
        if (target < 0 || target >= providers.size) return
        val item = providers.removeAt(index)
        providers.add(target, item)
        val newSettings = _state.value.settings.copy(providers = providers)
        FuelSettingsStore.saveMultiProvider(newSettings)
        _state.update { it.copy(settings = newSettings) }
    }

    // --- Agent settings ---

    /**
     * Adds a new ACP agent to settings.
     * Persists immediately and notifies the callback (main.kt restarts the manager).
     */
    fun addAgent(name: String, command: String, args: String) {
        val newConfig = AgentConfig(
            id = AgentSettingsStore.generateAgentId(),
            name = name,
            command = command,
            args = args,
        )
        val updated = _state.value.agentSettings.copy(
            agents = _state.value.agentSettings.agents + newConfig,
        )
        AgentSettingsStore.save(updated)
        _state.update { it.copy(agentSettings = updated) }
        onAgentSettingsChanged?.invoke(updated)
    }

    /**
     * Removes an ACP agent from settings by ID.
     */
    /**
     * Moves an agent up (-1) or down (+1) in the user-ordered list.
     * Order persists in AgentSettings and syncs across devices.
     */
    fun moveAgent(agentId: String, offset: Int) {
        val agents = _state.value.agentSettings.agents.toMutableList()
        val index = agents.indexOfFirst { it.id == agentId }
        if (index < 0) return
        val target = index + offset
        if (target < 0 || target >= agents.size) return
        val item = agents.removeAt(index)
        agents.add(target, item)
        val updated = _state.value.agentSettings.copy(agents = agents)
        AgentSettingsStore.save(updated)
        _state.update { it.copy(agentSettings = updated) }
        onAgentSettingsChanged?.invoke(updated)
        // Reorder the display list to match (MCP/HTTP-registered agents not
        // in settings keep their relative order at the end)
        val displayById = _state.value.acpAgents.associateBy { it.id }
        val agentIds = agents.map { it.id }.toSet()
        val reordered = agents.mapNotNull { displayById[it.id] }
        val extras = _state.value.acpAgents.filter { it.id !in agentIds }
        _state.update { it.copy(acpAgents = reordered + extras) }
    }

    fun removeAgent(agentId: String) {        // Remove from ACP agent settings
        val updated = _state.value.agentSettings.copy(
            agents = _state.value.agentSettings.agents.filterNot { it.id == agentId },
        )
        AgentSettingsStore.save(updated)
        _state.update { it.copy(agentSettings = updated) }
        onAgentSettingsChanged?.invoke(updated)
        // Also remove from MCP/HTTP registered agents
        onRemoveAgent?.invoke(agentId)
        // Remove from all source lists so the next merge doesn't re-add it
        acpDiscoveredAgents.removeAll { it.id == agentId }
        registeredAgents.removeAll { it.id == agentId }
        orchestratorAgents.removeAll { it.id == agentId }
        // Remove from the displayed agent list
        _state.update { state ->
            state.copy(
                acpAgents = state.acpAgents.filterNot { a -> a.id == agentId },
            )
        }
    }

    /**
     * Imports synced settings from a QR code scan, text code, or server sync.
     *
     * Scope-routed: full payloads replace providers, agents, theme, and all
     * preferences; settings-scope payloads apply everything EXCEPT agent
     * configs; agents-scope payloads apply ONLY agent configs. Legacy
     * payloads without a scope field behave as full.
     */
    fun importSyncedSettings(syncData: com.angussoftware.fueldashboard.model.SettingsSyncData) {
        val isAgentsOnly = syncData.scope == com.angussoftware.fueldashboard.model.SettingsSyncData.SCOPE_AGENTS
        val isSettingsOnly = syncData.scope == com.angussoftware.fueldashboard.model.SettingsSyncData.SCOPE_SETTINGS

        // NOTE on serverApiKey: this path deliberately does NOT write
        // syncData.serverApiKey to ServerApiKeyStore (unlike the server's
        // legacy fallback path). The payload's key is the SENDER's copy of
        // THIS device's key — saving it would be a self-no-op in the normal
        // case and would clobber this device's own key when importing a
        // foreign dashboard's settings. The key lands in the CONNECTED_API
        // provider entry below, which is the correct destination for it.

        // Section orders (Usage/Intel tabs) — empty lists keep the receiver's own.
        if (!isAgentsOnly && syncData.usageSectionOrder.isNotEmpty()) {
            com.angussoftware.fueldashboard.settings.SectionOrder.save(
                com.angussoftware.fueldashboard.settings.FuelSettingsKeys.SECTION_ORDER_USAGE,
                syncData.usageSectionOrder,
            )
        }
        if (!isAgentsOnly && syncData.intelSectionOrder.isNotEmpty()) {
            com.angussoftware.fueldashboard.settings.SectionOrder.save(
                com.angussoftware.fueldashboard.settings.FuelSettingsKeys.SECTION_ORDER_INTEL,
                syncData.intelSectionOrder,
            )
        }
        // Merge: take synced providers AND add/update a Remote Dashboard provider with the server API key
        if (!isAgentsOnly) {
            val hasServer = syncData.serverUrl != null
            val providers = syncData.providers.map { p ->
                // When a Remote Dashboard comes along, direct providers are
                // display-only on this device: the phone re-polling every
                // account the desktop already polls doubles quota burn
                // against the same accounts. Tiles hydrate from the remote
                // snapshot instead (see refresh's dormant hydration).
                // NEVER accept a switch command from a synced payload. It is a
                // command this machine would later execute on its own, so
                // honouring it would turn "import settings" — a QR scan or a
                // POST /sync — into arbitrary code execution on the importer.
                // It is machine-specific anyway: the binary it names need not
                // exist here. Set it locally or not at all.
                //
                // A credential REFERENCE is stripped for the same reason:
                // `cmd:` would be executed by THIS machine to fetch the key,
                // and `file:` would read a local path of the sender's choosing
                // and send its contents to a provider. Literal keys sync as
                // they always have.
                //
                // CLAUDE_CODE's serverUrl is stripped for the same reason as
                // a `file:` reference: this kind authenticates with the
                // machine-local Claude Code OAuth token, so an imported
                // serverUrl would direct that credential at a host of the
                // sender's choosing. The default (api.anthropic.com) is the
                // only destination a synced CLAUDE_CODE provider may have.
                val safe = p.copy(
                    activateCommand = "",
                    swapAwayBelowPct = 0,
                    apiKey = if (SecretRef.isReference(p.apiKey)) "" else p.apiKey,
                    serverUrl = if (p.kind == com.angussoftware.fueldashboard.model.ProviderKind.CLAUDE_CODE) "" else p.serverUrl,
                )
                if (hasServer && safe.kind != com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API) {
                    safe.copy(dormant = true)
                } else {
                    safe
                }
            }.toMutableList()
            syncData.serverUrl?.let { url ->
                val key = syncData.serverApiKey.orEmpty()
                // Remove any existing CONNECTED_API and add fresh one
                providers.removeAll { it.kind == com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API }
                providers.add(
                    com.angussoftware.fueldashboard.model.ProviderConfig(
                        id = "synced-orchestrator",
                        kind = com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API,
                        apiKey = key,
                        displayName = "Remote Dashboard",
                        serverUrl = url,
                    ),
                )
            }
            updateSettings(com.angussoftware.fueldashboard.model.MultiProviderSettings(providers = providers))
        }

        if (!isSettingsOnly) {
            AgentSettingsStore.save(syncData.agentSettings)
            _state.update { it.copy(agentSettings = syncData.agentSettings) }
            onAgentSettingsChanged?.invoke(syncData.agentSettings)

            // Re-materialize config-only agent display entries so synced
            // agents appear immediately and survive restarts. This merges
            // with any live/remote agents already in the list.
            materializeConfigAgents()
        }

        if (!isAgentsOnly) {
            // Apply theme settings
            val themeController = com.angussoftware.fueldashboard.settings.ThemeController

            // Apply Junie balance data (synced from desktop)
            syncData.junieBalance?.let { balance ->
                saveStringSetting(FuelSettingsKeys.JUNIE_BALANCE, balance.toString())
            }
            syncData.junieLicense?.let { license ->
                saveStringSetting(FuelSettingsKeys.JUNIE_LICENSE, license)
            }
            syncData.junieLastChecked?.let { checked ->
                saveStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, checked.toString())
            }

            runCatching {
                val mode = com.angussoftware.theming.compose.ui.theme.ThemeMode.valueOf(syncData.themeMode)
                themeController.updateThemeMode(mode)
            }
            runCatching {
                val light = com.angussoftware.theming.compose.ui.theme.ColorTheme.valueOf(syncData.lightColorTheme)
                themeController.updateLightColorTheme(light)
            }
            runCatching {
                val dark = com.angussoftware.theming.compose.ui.theme.ColorTheme.valueOf(syncData.darkColorTheme)
                themeController.updateDarkColorTheme(dark)
            }

            // Usage ingestion sources (Letta server config) — takes effect on the
            // next ingestion poll (manager re-reads the store each cycle).
            syncData.usageSources?.let { sources ->
                com.angussoftware.fueldashboard.settings.UsageSourcesStore.save(sources)
            }

            // Intelligence drop threshold
            syncData.eventDropThresholdPct?.let { threshold ->
                saveStringSetting(FuelSettingsKeys.EVENT_DROP_THRESHOLD, threshold.toString())
            }

            // Preferences — null keeps the receiver's own
            syncData.showHelp?.let { help -> setShowHelp(help) }
            syncData.showThemeIcon?.let { showIcon -> setShowThemeIcon(showIcon) }
            syncData.showAdvisor?.let { showAdvisor -> setShowAdvisor(showAdvisor) }

            // Custom feedback endpoints
            syncData.feedbackUrl?.let { url -> saveStringSetting(FuelSettingsKeys.FEEDBACK_URL, url) }
            syncData.feedbackRepo?.let { repo -> saveStringSetting(FuelSettingsKeys.FEEDBACK_REPO, repo) }
        }

        // Remote Dashboard provider is already added above in the providers list

        // Bump lastUpdated: UI caches keyed on it (e.g. mobile section order)
        // re-read their prefs after an import instead of showing stale order
        // until the tab is left and re-entered.
        _state.update { it.copy(lastUpdated = epochMillis()) }
    }

    // --- Internals ---

    private fun activateAdapters(settings: MultiProviderSettings) {
        for (config in settings.providers) {
            if (!config.isConfigured) continue
            // Dormant providers are display-only (synced alongside a Remote
            // Dashboard) — no local adapter, no local polling. Their tiles
            // hydrate from the remote snapshot in refresh().
            if (config.dormant) continue
            val adapter = createAdapter(config) ?: continue
            adapters[config.id] = adapter
        }
    }

    private fun createAdapter(config: ProviderConfig): ProviderAdapter? {
        // Resolved once per activation, never stored. For a pasted key this is
        // the string itself — no I/O, no process, nothing changes.
        val ref = SecretRef.parse(config.apiKey)
        val resolvedKey = resolveSecretRef(ref)
        if (resolvedKey == null) {
            secretResolutionErrors[config.id] = when (ref) {
                is SecretRef.Command ->
                    "Could not run ${SecretRef.describe(config.apiKey)} — is the helper on PATH and the vault unlocked?"
                is SecretRef.Environment ->
                    "${SecretRef.describe(config.apiKey)} is not set in this app's environment"
                is SecretRef.FileContents ->
                    "Could not read ${SecretRef.describe(config.apiKey)}"
                is SecretRef.Literal -> "No API key configured"
            }
            return null
        }
        secretResolutionErrors.remove(config.id)

        return when (config.kind) {
            ProviderKind.ZAI -> ZaiProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.LETTA_CLOUD -> LettaCloudProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.OPENAI -> OpenAIProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.ANTHROPIC -> AnthropicProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.DEEPSEEK -> DeepSeekProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.GROQ -> GroqProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.MISTRAL -> MistralProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.OPENROUTER -> OpenRouterProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.GEMINI -> GeminiProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.XAI -> XaiProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.QWEN -> QwenProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.TOGETHER -> TogetherProviderAdapter(
                providerId = config.id,
                apiKey = resolvedKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.JUNIE -> JunieProviderAdapter(
                providerId = config.id,
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.CLAUDE_CODE -> ClaudeCodeSubscriptionAdapter(
                providerId = config.id,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.CONNECTED_API -> ConnectedApiProviderAdapter(
                providerId = config.id,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
                apiKey = resolvedKey,
            )
        }
    }

    private fun closeAdapters() {
        adapters.values.forEach { runCatching { it.close() } }
        adapters.clear()
    }

    /**
     * Poll interval: 30s for all providers.
     * Connected API was previously 30s, direct providers 5min.
     * Unified to 30s for responsive UX — provider APIs can handle it.
     */
    private fun pollIntervalMs(): Long = 30_000L

    /**
     * Effective poll interval for one provider: the configured interval with
     * exponential failure backoff applied (x2 per consecutive failure, x32
     * cap) and a 30-minute backoff ceiling. A base interval above 30 minutes
     * is honored as configured (the ceiling constrains BACKOFF, not the
     * user's chosen cadence). Pure — unit-testable.
     */
    /**
     * Floor on how often a provider kind may be polled, whatever the user set.
     *
     * Claude Code's endpoint reports a 5-hour and a 7-day window. Polling it
     * every 30s cannot resolve either one any better — the number does not
     * move between polls — but it is 600 requests per 5-hour window, and
     * api.anthropic.com rate-limits it. 5 minutes still gives 60 reads per
     * window. Manual Refresh is unaffected; this only bounds the timer.
     */
    internal fun minPollIntervalSec(kind: ProviderKind): Int = when (kind) {
        ProviderKind.CLAUDE_CODE -> 300
        else -> 15
    }

    internal fun effectiveIntervalMs(baseIntervalSec: Int, consecutiveFailures: Int): Long {
        val base = baseIntervalSec.coerceAtLeast(15) * 1000L
        if (base >= 30 * 60_000L) return base
        return (base * (1L shl consecutiveFailures.coerceAtMost(5)))
            .coerceAtMost(30 * 60_000L)
    }

    // Per-provider poll scheduling: last time each provider was actually
    // polled, used to honor ProviderConfig.pollIntervalSeconds inside the
    // 30s global loop.
    private val lastPolledMs = mutableMapOf<String, Long>()

    /** Consecutive poll failures per provider — drives exponential backoff. */
    private val consecutiveFailures = mutableMapOf<String, Int>()

    /**
     * Earliest time a provider may be polled again, when the server itself
     * said so (HTTP Retry-After). Honoured ahead of our own cadence: asking
     * again before then is what keeps a rate limit alive.
     */
    private val rateLimitedUntil = mutableMapOf<String, Long>()

    /**
     * Longest a server's Retry-After may park a provider — the same
     * 30-minute ceiling the exponential failure backoff already uses, so the
     * two agree on how long is too long to go quiet.
     */
    internal val maxRateLimitParkMs = 30L * 60 * 1000

    /**
     * When a provider parked by [retryAfterMs] may be polled again.
     *
     * A function rather than an inline expression so the bound is exercised
     * directly by tests: a test that re-implements the arithmetic only proves
     * the test can multiply.
     */
    internal fun parkUntil(now: Long, retryAfterMs: Long): Long =
        now + retryAfterMs.coerceAtMost(maxRateLimitParkMs)

    /**
     * Consecutive polls that SUCCEEDED but carried no usable reading, per
     * provider id.
     *
     * A poll that throws is an error and shows as one. This counts the other
     * case: the provider answered, and the answer was empty. Right after
     * start that is usually just a provider that has not settled — the Claude
     * Code usage endpoint returns a payload with no utilization for a moment
     * — so the first one must not be announced as UNAVAILABLE. A second one
     * in a row means it really has nothing to tell us.
     */
    private val consecutiveUnavailable = mutableMapOf<String, Int>()

    /**
     * Bumped on every applySettings. refresh() captures it at start and
     * discards its results (logging + state write) if it changed mid-flight —
     * kills the ghost-provider race where an in-flight refresh resurrects
     * just-removed providers into state, seeding them forever.
     */
    private val configGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private suspend fun refresh() = refreshMutex.withLock {
        val generationAtStart = configGeneration.get()
        // Snapshot adapters to avoid ConcurrentModificationException if
        // applySettings mutates the map during iteration.
        val adapterSnapshot = adapters.toList()
        if (adapterSnapshot.isEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false,
                    providerErrors = mapOf("global" to "No providers configured"),
                )
            }
            return
        }

        // Honor per-provider intervals: only poll adapters whose interval
        // has elapsed since their last poll. Skipped providers keep their
        // existing report (reports persist in state between refreshes).
        // Consecutive failures back off exponentially (x2 per failure, cap
        // 30 min): a revoked key or dead endpoint otherwise gets hammered
        // at full cadence indefinitely — 1,440+ wasted requests/day against
        // an account that is usually already rate-limited or suspended.
        val nowMs = epochMillis()
        val dueAdapters = adapterSnapshot.filter { (providerId, _) ->
            // A server that told us how long to wait outranks our own timer.
            val parkedUntil = rateLimitedUntil[providerId]
            if (parkedUntil != null && nowMs < parkedUntil) return@filter false

            val config = _state.value.settings.providers.firstOrNull { it.id == providerId }
            val configured = config?.pollIntervalSeconds ?: 60
            val intervalSec = config?.let { maxOf(configured, minPollIntervalSec(it.kind)) } ?: configured
            val intervalMs = effectiveIntervalMs(intervalSec, consecutiveFailures[providerId] ?: 0)
            val last = lastPolledMs[providerId]
            last == null || nowMs - last >= intervalMs
        }
        dueAdapters.forEach { (providerId, _) -> lastPolledMs[providerId] = nowMs }

        // Poll due provider adapters in parallel
        val reportResults = dueAdapters.map { (providerId, adapter) ->
            scope.async {
                try {
                    providerId to Result.success(adapter.poll())
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e // cancellation must propagate, not become a provider error
                } catch (e: Exception) {
                    providerId to Result.failure(e)
                }
            }
        }.awaitAll()

        // Generation guard (1 of 2): if settings changed while our polls were
        // in flight (applySettings runs OUTSIDE refreshMutex), this refresh's
        // results belong to a superseded configuration — skip the logging
        // side effects; the final state write re-checks (2 of 2) inside the
        // CAS loop, which closes the race completely: if applySettings wins
        // the CAS first, our update lambda re-runs against the new state and
        // the generation check discards the stale write (ghost-provider fix).
        if (configGeneration.get() != generationAtStart) return

        val reports = mutableMapOf<String, ProviderReport>()
        // Seed with any credential that could not be resolved, so the tile
        // explains itself instead of showing an unconfigured provider or an
        // opaque 401 from polling with no key.
        val errors = mutableMapOf<String, String>()
        errors.putAll(secretResolutionErrors)

        // Providers skipped by their interval keep their previous report —
        // seed them so the wholesale state replace doesn't blank their tiles.
        val dueIds = dueAdapters.map { it.first }.toSet()
        _state.value.providerReports.forEach { (id, existing) ->
            if (id !in dueIds) reports[id] = existing
        }

        for ((providerId, result) in reportResults) {
            result
                .onSuccess {
                    reports[providerId] = it
                    consecutiveFailures[providerId] = 0
                    if (it.available) {
                        consecutiveUnavailable.remove(providerId)
                    } else {
                        consecutiveUnavailable[providerId] =
                            (consecutiveUnavailable[providerId] ?: 0) + 1
                    }
                }
                .onFailure {
                    errors[providerId] = it.message ?: "Unknown error"
                    consecutiveFailures[providerId] = (consecutiveFailures[providerId] ?: 0) + 1
                    // An empty reading is not what failed here — drop any
                    // settling streak so a failure shows as a failure.
                    consecutiveUnavailable.remove(providerId)
                    val retryAfter = (it as? ClaudeCodeUsageHttpException)?.retryAfterMs
                    if (retryAfter != null) {
                        // Capped at the same ceiling the failure backoff uses.
                        // An honest Retry-After of hours is plausible for a
                        // quota reset, but so is a malformed one — seconds
                        // sent where milliseconds were meant parks a provider
                        // for a day, which is indistinguishable from a hang.
                        // Respecting the cap costs at most one request per
                        // ceiling: if the server really wants longer it
                        // answers 429 again and we re-park.
                        rateLimitedUntil[providerId] = parkUntil(epochMillis(), retryAfter)
                    }
                }
        }

        // Extract orchestrator data from any connected API adapter
        var fuel: FuelResponse? = null
        var decisions = DecisionsResponse()
        var agents = AgentsResponse()
        var alerts = AlertsResponse()

        for ((providerId, _) in reports) {
            val adapter = adapterSnapshot.find { it.first == providerId }?.second
            if (adapter is ConnectedApiProviderAdapter) {
                fuel = adapter.lastFuel
                decisions = adapter.lastDecisions
                agents = adapter.lastAgents
                alerts = adapter.lastAlerts
                // Sync Junie balance from remote dashboard to local settings
                adapter.lastFuel?.junie?.let { junie ->
                    junie.balance?.let { saveStringSetting(FuelSettingsKeys.JUNIE_BALANCE, it.toString()) }
                    junie.license?.let { saveStringSetting(FuelSettingsKeys.JUNIE_LICENSE, it) }
                    junie.lastChecked?.let { saveStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, it.toString()) }
                }
                break
            }
        }

        // ── Load local decisions if no ConnectedApi provided them ──────
        if (decisions.decisions.isEmpty()) {
            decisions = DecisionsResponse(decisions = onFetchDecisions?.invoke() ?: emptyList())
        }

        // ── Standalone alert generation ──────────────────────────────────
        // If no connected API (orchestrator) is providing alerts, generate
        // them locally from provider fuel percentages.
        val generatedAlerts = generateFuelAlerts(
            reports = reports,
            servingProviderId = _state.value.claudeCodeRoute?.matchedProviderId,
        )
        // Merge: if the orchestrator provided alerts, use those + generated.
        // Otherwise, use generated alone.
        if (generatedAlerts.isNotEmpty()) {
            alerts = AlertsResponse(alerts.alerts + generatedAlerts)
        }

        // ── Connected-mode data parity (mobile) ────────────────────────────
        // When a Remote Dashboard is configured, fetch its /dashboard snapshot
        // and populate the metered / intelligence state locally — mobile has
        // no local repos, so this is the ONLY source of usage/waste/events
        // data on Android.
        // Single /dashboard fetch: the connected adapter's poll already
        // fetched and parsed the complete snapshot (consolidated from the
        // old 4-sub-fetch + separate snapshot fetch = 5 requests/refresh).
        // Reuse it — zero extra requests, and it honors the provider's
        // poll interval instead of firing every 30s tick.
        val connectedAdapterEntry = adapterSnapshot.firstOrNull {
            it.second is com.angussoftware.fueldashboard.network.ConnectedApiProviderAdapter
        }
        val remoteSnapshot = connectedAdapterEntry
            ?.let { it.second as com.angussoftware.fueldashboard.network.ConnectedApiProviderAdapter }
            ?.lastSnapshot
        val remoteMetered = remoteSnapshot?.metered
        val remoteIntelligence = remoteSnapshot?.intelligence

        // Connected-mode gauge parity: current servers serve provider gauges
        // on /dashboard, not the legacy /fuel endpoint (providers: {}). When
        // the connected adapter's report has no windows, source quota lines
        // from the snapshot. Runs BEFORE snapshot logging so fuel history,
        // projection, and burn rates also see the gauges — not just the
        // Fuel tab cards and status notification.
        if (remoteSnapshot != null && remoteSnapshot.providers.isNotEmpty()) {
            for ((providerId, adapter) in adapterSnapshot) {
                if (adapter !is com.angussoftware.fueldashboard.network.ConnectedApiProviderAdapter) continue
                val existing = reports[providerId] ?: continue
                if (existing.windows.isNotEmpty()) continue
                val windows = remoteSnapshot.providers.map { p ->
                    ReportWindow(
                        name = p.name,
                        remainingPct = p.remainingPct,
                        resetsAt = p.resetsAt,
                        windowHours = p.windowHours,
                    )
                }
                // Headline must come from ONE gauge: %, reset, and window from
                // the same provider so heterogeneous multi-provider remotes
                // never pair provider B's percentage with provider A's
                // countdown (review finding, PR #84).
                val headline = windows.firstOrNull { it.remainingPct != null }
                reports[providerId] = existing.copy(
                    remainingPct = headline?.remainingPct ?: existing.remainingPct,
                    resetsAt = headline?.resetsAt ?: existing.resetsAt,
                    windowHours = headline?.windowHours?.takeIf { it > 0 } ?: existing.windowHours,
                    windows = windows,
                )
            }
        }

        // Dormant-provider hydration: synced direct providers (no local
        // adapter by design — the phone must not re-poll accounts the
        // desktop already polls) get their tiles from the same remote
        // snapshot. Zero extra requests: the gauges are already in hand.
        if (remoteSnapshot != null && remoteSnapshot.providers.isNotEmpty()) {
            val byId = remoteSnapshot.providers.associateBy { it.id }
            for (config in _state.value.settings.providers) {
                if (!config.dormant) continue
                val gauge = byId[config.id] ?: continue
                reports[config.id] = ProviderReport(
                    providerId = config.id,
                    displayName = gauge.name.ifBlank { config.resolvedDisplayName() },
                    type = ProviderType.WINDOW_CREDIT,
                    remainingPct = gauge.remainingPct,
                    resetsAt = gauge.resetsAt,
                    windowHours = gauge.windowHours,
                    available = gauge.remainingPct != null,
                    windows = listOf(
                        ReportWindow(
                            name = gauge.name.ifBlank { config.resolvedDisplayName() },
                            remainingPct = gauge.remainingPct,
                            resetsAt = gauge.resetsAt,
                            windowHours = gauge.windowHours,
                        ),
                    ),
                    rawDisplay = if (gauge.remainingPct != null) {
                        "${gauge.remainingPct}% via Remote Dashboard"
                    } else {
                        "via Remote Dashboard"
                    },
                )
            }
        }

        // ── Real fuel tracking — ALL providers ─────────────────────────────
        // Log every provider's fuel state, not just the first one.
        val activeAgents = _state.value.acpAgents.filter { it.status == "connected" }
        val activeModels = activeAgents.mapNotNull { it.currentModel }.distinct().sorted()

        // Build per-provider snapshot inputs for ALL providers with fuel data
        val providerSnapshots = reports.mapNotNull { (providerId, report) ->
            if (report.remainingPct == null) return@mapNotNull null
            val config = _state.value.settings.providers.find { it.id == providerId }
            val quotaType = classifyQuotaType(report)
            ProviderSnapshotInput(
                providerId = providerId,
                providerName = config?.resolvedDisplayName() ?: report.displayName,
                providerType = report.type.name,
                remainingPct = report.remainingPct.toDouble(),
                resetAt = report.resetsAt,
                windowHours = report.windowHours,
                quotaType = quotaType,
            )
        }

        // Log all provider snapshots
        if (providerSnapshots.isNotEmpty()) {
            onLogProviderSnapshots?.invoke(providerSnapshots)
            maybeRunSwitchCommands(reports)
        }

        // Pick the primary provider deterministically: first configured
        // provider in settings order (not HashMap iteration order).
        val primaryProviderId = _state.value.settings.providers
            .firstOrNull { it.isConfigured }?.id
        val primaryReport = primaryProviderId?.let { providerSnapshots.find { s -> s.providerId == it } }
            ?: providerSnapshots.firstOrNull()
        val tokensPct = primaryReport?.remainingPct
        val resetAt = primaryReport?.resetAt
        onLogFuelSnapshot?.invoke(
            tokensPct,
            null,
            activeAgents.size,
            activeModels.joinToString(","),
            resetAt,
        )

        // Compute per-provider burn rates
        val providerBurnRates = onGetProviderBurnRates?.invoke() ?: emptyList()
        val metered = onGetMeteredUsage?.invoke()
        val intelligence = onGetIntelligence?.invoke()

        // Use the PRIMARY provider's burn rate for the legacy projection.
        // Keyed by providerId — providerBurnRates comes from an unordered
        // SELECT DISTINCT, so firstOrNull() could pair this provider's gauge
        // with a different provider's burn rate. Fall back to the first
        // non-null rate only when the primary has none.
        val realBurnRate = providerBurnRates.find { it.providerId == primaryProviderId }?.burnRatePerHr
            ?: providerBurnRates.firstNotNullOfOrNull { it.burnRatePerHr }
        var fuelProjection: FuelProjection? = null
        if (tokensPct != null) {
            fuelProjection = onGetProjection?.invoke(tokensPct, resetAt, realBurnRate ?: 0.0)
            if (fuelProjection != null) {
                fuel = (fuel ?: FuelResponse()).copy(
                    burnRatePctPerHr = realBurnRate ?: 0.0,
                )
            }
        }

        // Only log a "decision" if the recommended model from orchestrator changed
        // (or the first time we see one). This prevents the 18k garbage rows problem.
        val currentFuel = fuel
        val recommendedModel = currentFuel?.recommendedModel ?: ""
        if (recommendedModel.isNotBlank() && recommendedModel != _lastRecommendation) {
            _lastRecommendation = recommendedModel
            onDecisionLogged?.invoke(
                "orchestrator",
                recommendedModel,
                "connected-api",
                "standard",
                "standard",
                0.0,
                tokensPct?.toInt() ?: 0,
                "model recommended by orchestrator",
            )
        }

        // Derive burn rate and data-point count from the primary provider's
        // per-provider history (correctly keyed by providerId). The old path
        // wrote ALL WINDOW_CREDIT providers into a single legacy FuelHistoryStore,
        // mixing series from different providers → garbage OLS burn rate.
        val primaryBurnRate = providerBurnRates.find { it.providerId == primaryProviderId }
            ?: providerBurnRates.firstOrNull()
        val dataPoints = primaryBurnRate?.history?.size ?: 0
        val burnRate = primaryBurnRate?.burnRatePerHr

        _state.update { current ->
            // Generation guard (2 of 2): a refresh that crossed a settings
            // change must not resurrect removed providers (ghost tiles +
            // phantom DB writes). MutableStateFlow.update is a CAS loop —
            // re-checking here makes the discard atomic with the write.
            if (configGeneration.get() != generationAtStart) {
                current.copy(isLoading = false)
            } else {
                current.copy(
            // Merge, not replace: fresh polls win per-key; non-polled and
            // out-of-band writes (e.g. checkJunieCredits) survive instead of
            // being clobbered by the next refresh's wholesale copy.
            providerReports = current.providerReports + reports,
            settlingProviderIds = (current.providerReports + reports)
                .filterValues { !it.available }
                .keys
                .filterTo(mutableSetOf()) { (consecutiveUnavailable[it] ?: 0) < UNAVAILABLE_STREAK },
            // A swap result describes one moment. Once the provider has
            // reported again the line is stale, so let the fresh gauge speak
            // instead — except for a swap still in flight, whose result has
            // not been written yet.
            switchResults = current.switchResults.filterKeys {
                it !in reports.keys || it in current.swappingProviderIds
            },
            providerErrors = errors,
            fuel = fuel,
            decisions = decisions,
            agents = agents,
            alerts = alerts,
            isLoading = false,
            lastUpdated = epochMillis(),
            // Only parks still in the future: an expired one is not a state
            // the card should keep announcing.
            rateLimitedUntil = rateLimitedUntil.filterValues { it > epochMillis() },
            // Re-read each poll: the file is rewritten underneath us whenever
            // the provider is switched, so a cached value would go stale
            // exactly when it matters most. Goes through the fleetReader seam
            // (not the top-level function) so injected test readers are
            // honoured and tests never touch a real ~/.claude.
            claudeCodeFleet = fleetReader(),
            claudeCodeRoute = routeReader()?.let { route ->
                route.copy(
                    matchedProviderId = ClaudeCodeRoute.matchProvider(
                        route.baseUrl,
                        current.settings.providers,
                    ),
                )
            },
            burnRate = if (burnRate != null && burnRate > 0) burnRate else null,
            dataPointCount = dataPoints,
            fuelProjection = fuelProjection,
            modelDrainRates = onGetModelDrainRates?.invoke() ?: emptyList(),
            fuelHistory = onGetFuelHistory?.invoke() ?: emptyList(),
            providerBurnRates = providerBurnRates,
            meteredBySource24h = metered?.bySource24h ?: remoteMetered?.bySource24h ?: emptyList(),
            meteredByModel24h = metered?.byModel24h ?: remoteMetered?.byModel24h ?: emptyList(),
            meteredBySource7d = metered?.bySource7d ?: remoteMetered?.bySource7d ?: emptyList(),
            meteredByModel7d = metered?.byModel7d ?: remoteMetered?.byModel7d ?: emptyList(),
            meteredByConversation24h = metered?.byConversation24h ?: remoteMetered?.byConversation24h ?: emptyList(),
            meteredByConversation7d = metered?.byConversation7d ?: remoteMetered?.byConversation7d ?: emptyList(),
            meteredByAgentModel24h = metered?.byAgentModel24h ?: remoteMetered?.byAgentModel24h ?: emptyList(),
            meteredByAgentModel7d = metered?.byAgentModel7d ?: remoteMetered?.byAgentModel7d ?: emptyList(),
            wasteByProvider = intelligence?.wasteByProvider ?: remoteIntelligence?.wasteByProvider ?: emptyList(),
            fuelEvents = intelligence?.fuelEvents ?: remoteIntelligence?.fuelEvents ?: emptyList(),
            fuelAdvice = intelligence?.advice ?: remoteIntelligence?.advice,
                )
            }
        }

        // Merge orchestrator agents into acpAgents so they show in the AgentPanel
        // (works on both desktop and mobile — desktop gets ACP agents via main.kt,
        // mobile gets orchestrator agents via ConnectedApiProviderAdapter)
        if (agents.agents.isNotEmpty()) {
            this.orchestratorAgents.clear()
            this.orchestratorAgents.addAll(agents.agents.map { agent ->
                AcpAgentDisplay(
                    id = agent.agentId,
                    name = agent.name,
                    currentModel = agent.currentModel.ifBlank { null },
                    availableModels = emptyList(),
                    currentMode = null,
                    availableModes = emptyList(),
                    status = "connected",
                    capabilities = emptyList(),
                )
            })
            mergeAcpAgents()
        }
    }

    fun close() {
        stopPolling()
        closeAdapters()
    }

    /**
     * Classifies a provider's quota type from its report.
     *
     * - WINDOW_CREDIT: Always a rate window (self-healing on a timer).
     *   Whether 5h (z.ai) or 27 days (z.ai session), hitting 0 just means
     *   throttling — it refills.
     * - SPEND_BUDGET with creditsResetAt: Credit pool (finite, refills on schedule).
     * - SPEND_BUDGET without creditsResetAt: Spend-only (finite, no refill).
     * - RATE_LIMIT: Always a rate window.
     */
    private fun classifyQuotaType(report: ProviderReport): QuotaType {
        return when (report.type) {
            ProviderType.WINDOW_CREDIT -> QuotaType.RATE_WINDOW
            ProviderType.RATE_LIMIT -> QuotaType.RATE_WINDOW
            ProviderType.SPEND_BUDGET -> {
                if (report.creditsResetAt != null) {
                    QuotaType.CREDIT_POOL
                } else {
                    QuotaType.SPEND_ONLY
                }
            }
        }
    }


    /**
     * Runs any provider's switch command whose quota just crossed its
     * threshold, and records the outcome.
     *
     * Reuses [onDecisionLogged] rather than adding a persistence path: that
     * callback already writes to the decision log, which is exactly where an
     * action the app took on its own belongs, and it means this feature needs
     * no wiring in the desktop entry point.
     */
    private suspend fun maybeRunSwitchCommands(reports: Map<String, ProviderReport>) {
        if (!switchCommandsSupported) return

        for (config in _state.value.settings.providers) {
            // Note this does NOT require config.activateCommand: that command
            // is how you arrive at this provider, and we are looking for
            // providers to leave.
            if (config.swapAwayBelowPct <= 0) continue

            val remaining = reports[config.id]?.takeIf { it.available }?.remainingPct
            val previous = switchTriggerState[config.id] ?: SwitchCommandTrigger.State()
            val (outcome, next) = SwitchCommandTrigger.evaluate(
                thresholdPct = config.swapAwayBelowPct,
                remainingPct = remaining,
                state = previous,
                now = epochMillis(),
            )
            // Always keep the advanced state: dropping it on a non-firing poll
            // resets the streak and agreement could never be reached.
            switchTriggerState[config.id] = next

            val fire = outcome as? SwitchCommandTrigger.Outcome.Fire ?: continue

            // Where to go. Nowhere better to be is a reason to stay put, not a
            // reason to move — so a missing target holds, exactly like a
            // refused fleet gate, and the agreement streak is preserved.
            val target = SwapTarget.choose(
                fromId = config.id,
                fromRemaining = remaining,
                providers = _state.value.settings.providers,
                // Only readings we actually have: an absent entry is unknown,
                // which SwapTarget refuses to treat as a usable tank.
                remainingById = reports.mapNotNull { (id, report) ->
                    report.takeIf { it.available }?.remainingPct?.let { id to it }
                }.toMap(),
            )
            if (target == null || executeSwitchCommand(
                    target,
                    reports[target.id]?.remainingPct,
                    "${fire.reason} — moving to ${target.resolvedDisplayName()}",
                ) is SwitchRun.Refused
            ) {
                // Roll back only the fire bookkeeping, keeping the agreement
                // streak. Persisting the fired state here would disarm the
                // trigger for an action that never happened, and it would not
                // re-arm until the provider recovered — silently skipping the
                // switch entirely.
                switchTriggerState[config.id] = next.copy(
                    armed = previous.armed,
                    lastFiredAt = previous.lastFiredAt,
                )
            }
        }
    }

    /** What [executeSwitchCommand] did, so each caller can do its own bookkeeping. */
    private sealed interface SwitchRun {
        /** The fleet gate said no. Nothing ran. */
        data class Refused(val fleet: ClaudeCodeFleet?) : SwitchRun
        data class Ran(val result: SwitchCommandResult) : SwitchRun
    }

    /**
     * The destructive part — fleet gate, process, decision-log row — in one
     * place, shared by the automatic trigger and the manual button so the two
     * can never drift apart on what is safe.
     *
     * Wait for quiet. A switch command is assumed destructive: the one this was
     * built for respawns every pane, killing whatever turn is in flight.
     * Completed turns are durable on disk; the live one is not, so a pending
     * action waits rather than interrupting.
     *
     * An unreadable fleet blocks too — "we cannot tell" must not read as
     * "nobody is working". That holds for a manual run as well: clicking a
     * button does not make someone else's live turn safe to kill.
     */
    private suspend fun executeSwitchCommand(
        config: ProviderConfig,
        remainingPct: Int?,
        reason: String,
        /**
         * Skip the fleet gate. Only ever true for a manual swap the operator
         * has already been refused once — the automatic trigger never sets
         * it, because nobody is there to accept the consequence.
         */
        force: Boolean = false,
    ): SwitchRun {
        val fleet = fleetReader()
        if (!force && (fleet == null || !fleet.isQuiet)) return SwitchRun.Refused(fleet)
        val overrode = force && (fleet == null || !fleet.isQuiet)

        // config is the provider being switched TO, so its activateCommand is
        // the one to run — for the manual button that is the card you clicked,
        // for the automatic trigger it is the target chosen by swapTargetFrom.
        val result = switchRunner(config.activateCommand)
        onDecisionLogged?.invoke(
            "switch-command",
            config.activateCommand.take(120),
            config.id,
            "action",
            if (result.succeeded) "ok" else "failed",
            (remainingPct ?: 0) / 100.0,
            remainingPct ?: 0,
            // An overridden gate is the single most important thing this log
            // can record: it is the one case where a killed turn was a choice
            // somebody made rather than something the app prevented.
            buildString {
                append(reason)
                if (overrode) append(" — FLEET GATE OVERRIDDEN")
                append(" — fleet ${fleet?.describe() ?: "unreadable"}")
                append(" — ${result.summary()}")
            },
        )
        return SwitchRun.Ran(result)
    }

    /**
     * Turns a finished command into an honest status by checking whether it
     * actually changed anything.
     *
     * An exit code says the command ran, not that the provider moved — and
     * those came apart immediately in practice: a placeholder command exited 0
     * and the card announced a swap that had not happened while the badge
     * still showed the old provider two lines above. Claiming success from an
     * exit code alone is a claim this app can check, so it checks.
     *
     * Three outcomes, deliberately distinct. "It did not take effect" and "I
     * could not tell" are different facts, and reporting the second as the
     * first would cry wolf every time the routing file is unreadable.
     */
    private fun verifiedStatus(target: ProviderConfig, result: SwitchCommandResult): SwitchRunStatus {
        if (!result.succeeded) {
            return SwitchRunStatus(ok = false, message = "Swap failed — ${result.summary()}")
        }

        val route = routeReader() ?: return SwitchRunStatus(
            ok = true,
            message = "Command succeeded, but the active provider could not be read to confirm it.",
        )
        val activeId = ClaudeCodeRoute.matchProvider(route.baseUrl, _state.value.settings.providers)

        return if (activeId == target.id) {
            SwitchRunStatus(ok = true, message = "Swapped to ${target.resolvedDisplayName()}.")
        } else {
            val activeName = _state.value.settings.providers
                .firstOrNull { it.id == activeId }
                ?.resolvedDisplayName()
                ?: route.label(_state.value.settings.providers)
            SwitchRunStatus(
                ok = false,
                message = "Command succeeded but nothing moved — Claude Code is still on " +
                    "$activeName. Does the command actually switch provider?",
            )
        }
    }

    /**
     * Fuel alerts for the providers we polled.
     *
     * Severity follows impact, not just the number. Running dry on the
     * provider actually serving requests stops work now; the same percentage
     * on one you are not routed to does not, and shouting equally about both
     * trains the eye to ignore the panel — with several providers configured,
     * idle accounts would fill it with CRITICALs that never mattered.
     *
     * A provider that is not in use still earns one quiet, unprefixed line
     * when it is empty, because it is the fallback you would swap to and
     * finding it gone at that moment is worse than a note beforehand.
     *
     * [servingProviderId] is null when we cannot tell which provider is
     * serving — for anyone not routing Claude Code through this app that is
     * always, so the original severities apply unchanged rather than being
     * silently downgraded.
     */
    internal fun generateFuelAlerts(
        reports: Map<String, ProviderReport>,
        servingProviderId: String?,
    ): List<String> = buildList {
        for ((providerId, report) in reports) {
            val pct = report.remainingPct ?: continue
            if (!report.available) continue
            val name = report.displayName.ifBlank { providerId }
            val inUse = servingProviderId == null || providerId == servingProviderId
            when {
                inUse && pct < 10 -> add("CRITICAL: $name at $pct%")
                inUse && pct < 25 -> add("WARNING: $name at $pct%")
                pct == 0 -> add("$name is empty (0%) — not in use")
                pct < 10 -> add("$name is nearly empty ($pct%) — not in use")
            }
        }
    }

    /** Why a swap was refused, phrased for the card rather than the log. */
    private fun refusalMessage(fleet: ClaudeCodeFleet?): String = when {
        fleet == null -> "Not swapped — could not read the Claude Code session registry."
        fleet.total == 0 -> "Not swapped — no Claude Code sessions are registered."
        fleet.busy > 0 -> "Not swapped — ${fleet.busy} of ${fleet.total} sessions still working. Let them finish."
        else -> "Not swapped — ${fleet.unknown} session(s) in an unknown state."
    }
}
