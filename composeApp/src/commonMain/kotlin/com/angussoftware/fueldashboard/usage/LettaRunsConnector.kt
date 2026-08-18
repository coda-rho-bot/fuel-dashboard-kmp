package com.angussoftware.fueldashboard.usage

import com.angussoftware.fueldashboard.database.UsageIngestionRepository
import com.angussoftware.fueldashboard.database.UsageRepository
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Usage source connector for Letta servers (cloud or self-hosted).
 *
 * Letta tracks exact per-run token usage server-side:
 *   GET /v1/runs?limit=N         → recent runs (agent_id, created_at, completed_at)
 *   GET /v1/runs/{run_id}/usage  → prompt/completion/total tokens
 *   GET /v1/agents               → agent configs (llm_config.model)
 *
 * The connector polls these, attributes each run's tokens to the model that
 * served it (via the agent→model history), and writes normalized records
 * into the platform-neutral usage_records store. Runs do NOT carry the model
 * directly, so attribution joins on the agent's configured model at run time.
 *
 * All Letta API knowledge is contained in this single class — the ingestion
 * manager, storage, and display layers are source-blind.
 *
 * @param httpFetch injectable GET function returning the response body for a
 *   path (e.g. "/v1/runs?limit=100"). Production wires Ktor; tests wire fakes.
 */
class LettaRunsConnector(
    override val id: String = "letta_runs",
    override val displayName: String = "Letta (runs polling)",
    private val baseUrl: String,
    private val apiKey: String,
    private val usageRepository: UsageRepository,
    private val ingestionRepository: UsageIngestionRepository,
    private val httpFetch: suspend (path: String) -> String,
) : UsageSourceConnector {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class LettaRun(
        val id: String,
        val agent_id: String? = null,
        val created_at: String? = null,
        val completed_at: String? = null,
        val conversation_id: String? = null,
    )

    @Serializable
    private data class LettaRunUsage(
        val prompt_tokens: Long? = null,
        val completion_tokens: Long? = null,
        val total_tokens: Long? = null,
    )

    @Serializable
    private data class LettaAgent(
        val id: String,
        val name: String? = null,
        val llm_config: LettaLlmConfig? = null,
    )

    @Serializable
    private data class LettaConversation(
        val id: String,
        val summary: String? = null,
        val agent_id: String? = null,
        val created_at: String? = null,
    )

    @Serializable
    private data class LettaLlmConfig(
        val model: String? = null,
    )

    /** Agent name cache (agent_id → display name), refreshed with metadata. */
    // Copy-on-write snapshot: written by the poll coroutine, read from
    // display-build coroutines on other threads — a volatile Map swap is the
    // common-safe publication (no concurrent mutation of a shared HashMap).
    @kotlin.concurrent.Volatile
    private var agentNames: Map<String, String> = emptyMap()

    override suspend fun poll(): UsageSourceConnector.PollResult {
        val errors = mutableListOf<String>()
        var ingested = 0

        val runsBody = try {
            httpFetch("/v1/runs?limit=$RUNS_BATCH")
        } catch (e: Exception) {
            return UsageSourceConnector.PollResult(0, listOf("runs list failed: ${e.message}"))
        }

        val runs = try {
            json.decodeFromString(ListSerializer(LettaRun.serializer()), runsBody)
        } catch (e: Exception) {
            return UsageSourceConnector.PollResult(0, listOf("runs parse failed: ${e.message}"))
        }

        for (run in runs) {
            if (run.completed_at == null) continue // still executing — usage not final
            val createdAt = run.created_at?.let { parseIsoMillis(it) } ?: continue

            // Claim BEFORE fetching usage: concurrent polls can't double-ingest
            if (!ingestionRepository.claimRun(id, run.id)) continue

            val usage = try {
                json.decodeFromString(LettaRunUsage.serializer(), httpFetch("/v1/runs/${run.id}/usage"))
            } catch (e: Exception) {
                errors.add("usage fetch failed for ${run.id}: ${e.message}")
                null
            } ?: continue

            val inputTokens = usage.prompt_tokens ?: 0L
            val outputTokens = usage.completion_tokens ?: 0L
            if (inputTokens == 0L && outputTokens == 0L) continue

            val agentId = run.agent_id ?: "unknown-agent"
            val model = ingestionRepository.modelAtTime(agentId, createdAt)
                ?: ingestionRepository.openAgentModels()[agentId]
                ?: "unknown"

            usageRepository.insert(
                timestamp = createdAt,
                source = agentNames[agentId] ?: agentId,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                requestCount = 1,
                conversationId = run.conversation_id,
            )
            ingested++
        }

        return UsageSourceConnector.PollResult(ingested, errors)
    }

    override suspend fun refreshMetadata() {
        val agentsBody = try {
            httpFetch("/v1/agents?limit=200")
        } catch (e: Exception) {
            return // non-fatal: keep previous mappings
        }
        val agents = try {
            json.decodeFromString(ListSerializer(LettaAgent.serializer()), agentsBody)
        } catch (e: Exception) {
            return
        }
        for (agent in agents) {
            val model = agent.llm_config?.model ?: continue
            val name = agent.name?.substringBefore(",")?.trim().takeUnless { it.isNullOrEmpty() } ?: agent.id
            agentNames = agentNames + (agent.id to name)
            ingestionRepository.recordAgentModel(agent.id, name, model)
        }
        refreshConversationTitles()
    }

    /**
     * Fetches conversation summaries into the titles lookup so the UI can
     * show human-readable names instead of raw conversation UUIDs. Titles
     * are resolved at display time — no re-ingestion needed when they change.
     *
     * Most conversations have no server-side summary, so a fallback label
     * is derived from the owning agent's name and the creation date
     * ("Beacon · Aug 15") — still far more readable than a UUID.
     */
    private suspend fun refreshConversationTitles() {
        var after: String? = null
        for (page in 0 until CONVERSATION_TITLE_PAGES) {
            val path = "/v1/conversations?limit=200" + (after?.let { "&after=$it" } ?: "")
            val body = try {
                httpFetch(path)
            } catch (e: Exception) {
                return // non-fatal: keep existing titles
            }
            val convs = try {
                json.decodeFromString(ListSerializer(LettaConversation.serializer()), body)
            } catch (e: Exception) {
                return
            }
            if (convs.isEmpty()) break
            for (conv in convs) {
                val summary = conv.summary?.trim().takeUnless { it.isNullOrEmpty() }
                val title = summary ?: fallbackTitle(conv.agent_id, conv.created_at)
                usageRepository.upsertConversationTitle(conv.id, title)
            }
            after = convs.last().id
            if (convs.size < 200) break
        }
        backfillMissingTitles()
    }

    /**
     * Fetches titles for the given conversation IDs directly (bounded) and
     * upserts them. Called at display-build time when a panel result contains
     * conversations the bulk/backfill passes haven't titled yet — closes the
     * "raw ID until next metadata cycle" lag for newly-active conversations.
     */
    // In-flight title fetches: byConversation fires ensureConversationTitles
    // every poll while titles are missing; without a guard, overlapping
    // coroutines fetch the same conversations concurrently. Mutex-guarded
    // set — KMP-safe (no JVM synchronized in commonMain).
    private val titleFetchMutex = kotlinx.coroutines.sync.Mutex()
    private val inFlightTitleFetches = mutableSetOf<String>()

    override suspend fun ensureConversationTitles(conversationIds: List<String>) {
        val known = usageRepository.getConversationTitles().keys
        val toFetch = titleFetchMutex.withLock {
            conversationIds.distinct()
                .filter { it !in known && it !in inFlightTitleFetches }
                .take(TITLE_BACKFILL_BATCH)
                .also { inFlightTitleFetches.addAll(it) }
        }
        try {
            for (id in toFetch) {
                val body = try {
                    httpFetch("/v1/conversations/$id")
                } catch (e: Exception) {
                    continue
                }
            val conv = try {
                json.decodeFromString(LettaConversation.serializer(), body)
            } catch (e: Exception) {
                continue
            }
            val summary = conv.summary?.trim().takeUnless { it.isNullOrEmpty() }
                usageRepository.upsertConversationTitle(conv.id, summary ?: fallbackTitle(conv.agent_id, conv.created_at))
            }
        } finally {
            titleFetchMutex.withLock { inFlightTitleFetches.removeAll(toFetch) }
        }
    }

    /**
     * The /v1/conversations list is unreliable for full coverage (thousands of
     * migration-era conversations bury recent ones beyond the page window —
     * observed: a top-usage conversation absent from 8 pages but fetchable
     * directly). Conversations with usage records but no title get fetched
     * by ID — bounded per cycle so consecutive cycles converge.
     */
    private suspend fun backfillMissingTitles() {
        val missing = usageRepository.getUntitledUsageConversations(limit = TITLE_BACKFILL_BATCH)
        for (convId in missing) {
            val body = try {
                httpFetch("/v1/conversations/$convId")
            } catch (e: Exception) {
                continue // non-fatal: try again next cycle
            }
            val conv = try {
                json.decodeFromString(LettaConversation.serializer(), body)
            } catch (e: Exception) {
                continue
            }
            val summary = conv.summary?.trim().takeUnless { it.isNullOrEmpty() }
            val title = summary ?: fallbackTitle(conv.agent_id, conv.created_at)
            usageRepository.upsertConversationTitle(conv.id, title)
        }
    }

    /** "Beacon · Aug 15" for summary-less conversations; null when nothing is known. */
    private fun fallbackTitle(agentId: String?, createdAt: String?): String {
        val agent = agentId?.let { agentNames[it] } ?: "conversation"
        val date = createdAt?.let { ts ->
            runCatching {
                Instant.parse(ts).toLocalDateTime(TimeZone.currentSystemDefault())
                    .let { "${monthShort(it.monthNumber)} ${it.dayOfMonth}" }
            }.getOrNull()
        }
        return if (date != null) "$agent · $date" else agent
    }

    private fun monthShort(month: Int): String = when (month) {
        1 -> "Jan"; 2 -> "Feb"; 3 -> "Mar"; 4 -> "Apr"; 5 -> "May"; 6 -> "Jun"
        7 -> "Jul"; 8 -> "Aug"; 9 -> "Sep"; 10 -> "Oct"; 11 -> "Nov"; else -> "Dec"
    }

    companion object {
        private const val RUNS_BATCH = 200
        private const val CONVERSATION_TITLE_PAGES = 5 // up to 1000 conversations
        private const val TITLE_BACKFILL_BATCH = 50 // direct-ID fetches per cycle
    }
}
