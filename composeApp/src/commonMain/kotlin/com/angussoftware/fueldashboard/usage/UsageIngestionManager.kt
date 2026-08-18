package com.angussoftware.fueldashboard.usage

import com.angussoftware.fueldashboard.database.UsageIngestionRepository
import com.angussoftware.fueldashboard.database.UsageRepository
import com.angussoftware.fueldashboard.settings.UsageSourcesStore
import com.angussoftware.fueldashboard.util.epochMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Drives all configured usage source connectors.
 *
 * Poll cadence: runs every POLL_INTERVAL_MS; connector metadata (agent→model
 * mappings) refreshes every METADATA_EVERY_N_POLLS cycles because model
 * switches are rare while run volume is continuous.
 *
 * Settings are re-read every cycle — enabling a source or changing its API
 * key takes effect on the next poll without restarting the app.
 */
class UsageIngestionManager(
    private val usageRepository: UsageRepository,
    private val ingestionRepository: UsageIngestionRepository,
    private val httpClient: HttpClient,
) {
    private val _status = MutableStateFlow(IngestionStatus())
    val status: StateFlow<IngestionStatus> = _status

    private var managerScope: CoroutineScope? = null

    /** Connector instances keyed by settings type. Rebuilt when config changes. */
    // Volatile: written by the poll coroutine, read by ensureConversationTitles
    // from display-build coroutines — safe publication across threads.
    @kotlin.concurrent.Volatile
    private var connectors: List<UsageSourceConnector> = emptyList()
    private var lastConfigFingerprint: String? = null

    fun start(scope: CoroutineScope) {
        if (managerScope != null) return
        managerScope = scope
        scope.launch {
            var cycle = 0
            while (isActive) {
                try {
                    val config = UsageSourcesStore.load()
                    val fingerprint = config.let { "${it.letta.enabled}|${it.letta.baseUrl}|${it.letta.apiKey}" }
                    if (fingerprint != lastConfigFingerprint) {
                        connectors = buildConnectors(config)
                        lastConfigFingerprint = fingerprint
                        if (connectors.isNotEmpty()) {
                            // Fresh config: refresh attribution metadata immediately
                            connectors.forEach { it.refreshMetadata() }
                        }
                    }

                    if (connectors.isEmpty()) {
                        _status.value = _status.value.copy(
                            enabled = false,
                            lastError = null,
                        )
                    } else {
                        var totalIngested = 0
                        val errors = mutableListOf<String>()
                        for (connector in connectors) {
                            if (cycle % METADATA_EVERY_N_POLLS == 0) connector.refreshMetadata()
                            val result = connector.poll()
                            totalIngested += result.recordsIngested
                            errors.addAll(result.errors.map { "${connector.id}: $it" })
                        }
                        _status.value = _status.value.copy(
                            enabled = true,
                            lastPollAt = epochMillis(),
                            lastError = errors.firstOrNull(),
                            totalIngested = _status.value.totalIngested + totalIngested,
                        )
                        if (errors.isNotEmpty()) {
                            println("UsageIngestion: errors: $errors")
                        }
                    }
                } catch (e: Exception) {
                    _status.value = _status.value.copy(lastError = "manager: ${e.message}")
                }
                cycle++
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Delegates display-time title gap-fill to connectors (fire-and-forget safe). */
    suspend fun ensureConversationTitles(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        connectors.forEach { it.ensureConversationTitles(conversationIds) }
    }

    fun stop() {
        managerScope?.launch { } // no-op; loop exits with scope cancellation
        managerScope = null
    }

    private fun buildConnectors(config: UsageSourcesSettings): List<UsageSourceConnector> {
        val result = mutableListOf<UsageSourceConnector>()
        if (config.letta.enabled && config.letta.apiKey.isNotBlank()) {
            val base = config.letta.baseUrl.trimEnd('/')
            val key = config.letta.apiKey
            result.add(
                LettaRunsConnector(
                    baseUrl = base,
                    apiKey = key,
                    usageRepository = usageRepository,
                    ingestionRepository = ingestionRepository,
                    httpFetch = { path ->
                        httpClient.get("$base$path") {
                            header(HttpHeaders.Authorization, "Bearer $key")
                        }.bodyAsText()
                    },
                )
            )
        }
        return result
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L       // 5 minutes
        private const val METADATA_EVERY_N_POLLS = 6              // ~30 minutes
    }
}

/** Serializable usage-source configuration (see UsageSourcesStore). */
@Serializable
data class UsageSourcesSettings(
    val letta: LettaSourceConfig = LettaSourceConfig(),
)

@Serializable
data class LettaSourceConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.letta.com",
    val apiKey: String = "",
)

/** Live status surfaced to the Settings UI. */
data class IngestionStatus(
    val enabled: Boolean = false,
    val lastPollAt: Long? = null,
    val lastError: String? = null,
    val totalIngested: Long = 0,
)
