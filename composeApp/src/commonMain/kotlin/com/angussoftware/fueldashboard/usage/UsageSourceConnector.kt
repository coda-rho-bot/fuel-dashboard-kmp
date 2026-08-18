package com.angussoftware.fueldashboard.usage

/**
 * Pluggable pull-side usage ingestion source.
 *
 * The canonical usage interface is the platform-neutral push API
 * (POST /v1/usage, OTel GenAI-aligned schema). Connectors complement it for
 * platforms that already track usage server-side (e.g. agent runtimes with
 * per-run token statistics): they poll the platform and normalize its data
 * into the same usage_records store.
 *
 * All platform-specific knowledge (endpoints, auth, pagination, attribution
 * joins) lives inside the connector implementation. The core pipeline —
 * storage, aggregation, display, recommendation — is source-blind.
 */
interface UsageSourceConnector {
    /** Stable identifier used in run dedup keys and settings. */
    val id: String

    /** Human-readable name for settings/status UI. */
    val displayName: String

    /**
     * Polls the source and ingests any new usage records.
     * Must be idempotent — safe to call repeatedly.
     */
    suspend fun poll(): PollResult

    /** Refreshes platform-side metadata (e.g. agent→model mappings). */
    suspend fun refreshMetadata() {}

    /** Ensures titles exist for the given conversations (display-time gap fill). */
    suspend fun ensureConversationTitles(conversationIds: List<String>) {}

    data class PollResult(
        val recordsIngested: Int,
        val errors: List<String> = emptyList(),
    )
}
