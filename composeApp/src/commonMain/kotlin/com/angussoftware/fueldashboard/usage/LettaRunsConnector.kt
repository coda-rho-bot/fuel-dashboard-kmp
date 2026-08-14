package com.angussoftware.fueldashboard.usage

import com.angussoftware.fueldashboard.database.UsageIngestionRepository
import com.angussoftware.fueldashboard.database.UsageRepository
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
    private data class LettaLlmConfig(
        val model: String? = null,
    )

    /** Agent name cache (agent_id → display name), refreshed with metadata. */
    private val agentNames = mutableMapOf<String, String>()

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
            agentNames[agent.id] = name
            ingestionRepository.recordAgentModel(agent.id, name, model)
        }
    }

    companion object {
        private const val RUNS_BATCH = 200
    }
}
