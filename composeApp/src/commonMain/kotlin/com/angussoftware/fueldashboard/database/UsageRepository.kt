package com.angussoftware.fueldashboard.database

import com.angussoftware.fueldashboard.util.epochMillis
import app.cash.sqldelight.db.SqlDriver

data class UsageRecord(
    val id: Long,
    val timestamp: Long,
    val source: String,
    val model: String,
    val conversationId: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
)

data class UsageBySource(
    val source: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
)

data class UsageByModel(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
)

data class UsageByConversation(
    val conversationId: String,
    val source: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
)

data class UsageByAgentModel(
    val source: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
)

/**
 * Universal usage metering store.
 *
 * Accepts usage records from ANY source — any runtime, provider, or tool —
 * via the agnostic POST /v1/usage contract (or the MCP report_usage tool).
 * Schema aligns with OpenTelemetry GenAI semantic conventions:
 *   source → service.name, model → gen_ai.request.model,
 *   input_tokens → gen_ai.usage.input_tokens, output_tokens → gen_ai.usage.output_tokens.
 *
 * Attribution rides in the record itself (the `source` field carries
 * emitter identity) — no joining against runtime APIs required.
 */
class UsageRepository(driver: SqlDriver) {
    private val db = FuelDatabase(driver)
    private val queries = db.fuelDatabaseQueries

    fun insert(
        timestamp: Long,
        source: String,
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        requestCount: Long = 1,
        conversationId: String? = null,
    ) {
        queries.insertUsageRecord(
            timestamp = timestamp,
            source = source,
            model = model,
            conversation_id = conversationId,
            input_tokens = inputTokens,
            output_tokens = outputTokens,
            request_count = requestCount,
            recorded_at = epochMillis(),
        )
    }

    fun getSince(since: Long): List<UsageRecord> =
        queries.selectUsageSince(since).executeAsList().map { row ->
            UsageRecord(
                id = row.id,
                timestamp = row.timestamp,
                source = row.source,
                model = row.model,
                conversationId = row.conversation_id,
                inputTokens = row.input_tokens,
                outputTokens = row.output_tokens,
                requestCount = row.request_count,
            )
        }

    /** Per-source (agent/runtime/tool) totals since a cutoff. */
    fun getBySourceSince(since: Long): List<UsageBySource> =
        queries.selectUsageBySourceSince(since).executeAsList().map { row ->
            UsageBySource(
                source = row.source,
                inputTokens = row.total_input ?: 0L,
                outputTokens = row.total_output ?: 0L,
                requestCount = row.total_requests ?: 0L,
            )
        }

    /** Per-model totals since a cutoff. */
    fun getByModelSince(since: Long): List<UsageByModel> =
        queries.selectUsageByModelSince(since).executeAsList().map { row ->
            UsageByModel(
                model = row.model,
                inputTokens = row.total_input ?: 0L,
                outputTokens = row.total_output ?: 0L,
                requestCount = row.total_requests ?: 0L,
            )
        }

    /** Per-conversation totals since a cutoff (includes source + model for context). */
    fun getByConversationSince(since: Long): List<UsageByConversation> =
        queries.selectUsageByConversationSince(since).executeAsList().map { row ->
            UsageByConversation(
                conversationId = row.conversation_id ?: "",
                source = row.source,
                model = row.model,
                inputTokens = row.total_input ?: 0L,
                outputTokens = row.total_output ?: 0L,
                requestCount = row.total_requests ?: 0L,
            )
        }

    /** Agent × model cross-tab totals since a cutoff. */
    fun getByAgentModelSince(since: Long): List<UsageByAgentModel> =
        queries.selectUsageByAgentModelSince(since).executeAsList().map { row ->
            UsageByAgentModel(
                source = row.source,
                model = row.model,
                inputTokens = row.total_input ?: 0L,
                outputTokens = row.total_output ?: 0L,
                requestCount = row.total_requests ?: 0L,
            )
        }

    fun cleanup(olderThanMs: Long = 90L * 24 * 3_600_000) { // 90 days default
        queries.deleteOldUsageRecords(epochMillis() - olderThanMs)
    }

    /** Upserts one conversation title (id → human-readable summary). */
    fun upsertConversationTitle(conversationId: String, title: String) {
        queries.insertConversationTitle(conversationId, title, epochMillis())
    }

    /** All known conversation titles for display-time resolution. */
    fun getConversationTitles(): Map<String, String> =
        queries.selectAllConversationTitles().executeAsList().associate { it.conversation_id to it.title }

    /** Usage-recorded conversations that have no title yet (for targeted gap-fill). */
    fun getUntitledUsageConversations(limit: Int = 50): List<String> =
        queries.selectUntitledUsageConversations(limit.toLong()).executeAsList()
}
