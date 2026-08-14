package com.angussoftware.fueldashboard.usage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.angussoftware.fueldashboard.database.FuelDatabase
import com.angussoftware.fueldashboard.database.UsageIngestionRepository
import com.angussoftware.fueldashboard.database.UsageRepository
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end connector test against an in-memory database, a fake HTTP
 * layer, and a controlled clock. Verifies: run ingestion, token attribution,
 * model join, and — most importantly — idempotent dedupe across overlapping
 * poll windows plus correct attribution across a model switch.
 */
class LettaRunsConnectorTest {

    /** Fixed base time T0; tests advance the clock to straddle the model switch. */
    private val t0 = 1_786_000_000_000L
    private var nowMs = t0
    private val clock = { nowMs }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var usageRepo: UsageRepository
    private lateinit var ingestionRepo: UsageIngestionRepository

    /** Formats epoch-ms as ISO-8601 UTC (the Letta API's timestamp format). */
    private fun iso(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    private fun runsJson(vararg runs: Triple<String, String, Long?>): String =
        runs.joinToString(prefix = "[", postfix = "]", separator = ",") { (id, agent, completedAt) ->
            val created = t0 + id.substringAfter("run-").toInt() * 3_600_000L // run-N at T0 + N hours
            val convId = "conv-${id.substringAfter("run-")}"
            val completedField = completedAt
                ?.let { ",\"completed_at\":\"${iso(it)}\"" }
                ?: ",\"completed_at\":null"
            "{\"id\":\"$id\",\"agent_id\":\"$agent\",\"conversation_id\":\"$convId\",\"created_at\":\"${iso(created)}\"$completedField}"
        }

    private var currentRuns: String = ""
    private val usageBodies = mutableMapOf<String, String>()
    private val agentsJson = """
        [
          {"id":"agent-a","name":"Coda, Agent Conductor","llm_config":{"model":"glm-5.2"}},
          {"id":"agent-b","name":"Beacon, Personal Assistant","llm_config":{"model":"glm-4.7"}}
        ]
    """.trimIndent()

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FuelDatabase.Schema.create(driver)
        usageRepo = UsageRepository(driver)
        ingestionRepo = UsageIngestionRepository(driver, clock)

        // Default fixtures: run-1 (completed) at T0+1h, run-2 (running) at
        // T0+2h, run-3 (completed) at T0+3h — all attributed to agent-a
        currentRuns = runsJson(
            Triple("run-1", "agent-a", t0 + 3_600_000L + 5_000L),
            Triple("run-2", "agent-b", null),
            Triple("run-3", "agent-a", t0 + 3 * 3_600_000L + 8_000L),
        )
        usageBodies["run-1"] = """{"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200}"""
        usageBodies["run-3"] = """{"prompt_tokens":3000,"completion_tokens":400,"total_tokens":3400}"""
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun connector() = LettaRunsConnector(
        baseUrl = "http://test",
        apiKey = "test-key",
        usageRepository = usageRepo,
        ingestionRepository = ingestionRepo,
        httpFetch = { path ->
            when {
                path.startsWith("/v1/runs?") -> currentRuns
                path.startsWith("/v1/agents") -> agentsJson
                path.startsWith("/v1/runs/run-") -> {
                    val runId = path.trimStart('/').split("/")[2] // v1/runs/{id}/usage
                    usageBodies[runId] ?: error("unknown run $runId")
                }
                else -> error("unexpected path $path")
            }
        },
    )

    @Test
    fun ingestsCompletedRunsWithAttribution() = runBlocking {
        val c = connector()
        c.refreshMetadata() // seed agent→model history at T0

        val result = c.poll()

        assertEquals(2, result.recordsIngested) // run-2 excluded (not completed)
        assertEquals(0, result.errors.size)

        val bySource = usageRepo.getBySourceSince(0)
        val coda = bySource.first { it.source == "Coda" }
        assertEquals(4000L, coda.inputTokens) // 1000 + 3000
        assertEquals(600L, coda.outputTokens)

        val byModel = usageRepo.getByModelSince(0)
        assertEquals(4000L, byModel.first { it.model == "glm-5.2" }.inputTokens)
    }

    @Test
    fun secondPollIsIdempotent() = runBlocking {
        val c = connector()
        c.refreshMetadata()

        c.poll()
        val second = c.poll()

        assertEquals(0, second.recordsIngested) // all runs already claimed
        val bySource = usageRepo.getBySourceSince(0)
        assertEquals(4000L, bySource.first { it.source == "Coda" }.inputTokens) // no double-count
    }

    @Test
    fun modelSwitchSplitsAttributionInTime() = runBlocking {
        val c = connector()
        c.refreshMetadata() // agent-a → glm-5.2 valid from T0

        // 90 minutes pass; agent-a switches to glm-4.7.
        // Run-1 completed at T0+1h (before switch) → glm-5.2.
        // Run-3 completed at T0+3h (after switch) → glm-4.7.
        nowMs = t0 + 90 * 60_000L
        ingestionRepo.recordAgentModel("agent-a", "Coda", "glm-4.7")

        val result = c.poll()
        assertEquals(2, result.recordsIngested)

        val byModel = usageRepo.getByModelSince(0).associateBy { it.model }
        assertEquals(1000L, byModel["glm-5.2"]?.inputTokens)
        assertEquals(3000L, byModel["glm-4.7"]?.inputTokens)
    }

    @Test
    fun conversationIdIsStoredAndQueryable() = runBlocking {
        val c = connector()
        c.refreshMetadata()

        c.poll()

        // run-1 → conv-1 (agent-a/Coda, glm-5.2), run-3 → conv-3 (agent-a/Coda, glm-5.2)
        val byConv = usageRepo.getByConversationSince(0)
        assertEquals(2, byConv.size)
        val conv1 = byConv.first { it.conversationId == "conv-1" }
        assertEquals("Coda", conv1.source)
        assertEquals("glm-5.2", conv1.model)
        assertEquals(1000L, conv1.inputTokens)
        assertEquals(200L, conv1.outputTokens)
    }

    @Test
    fun conversationIdDistinguishesModelSwitch() = runBlocking {
        val c = connector()
        c.refreshMetadata()

        // Agent switches model after run-1 but before run-3
        nowMs = t0 + 90 * 60_000L
        ingestionRepo.recordAgentModel("agent-a", "Coda", "glm-4.7")

        c.poll()

        // Both runs are from the same agent but different conversations
        val byConv = usageRepo.getByConversationSince(0).sortedBy { it.conversationId }
        assertEquals(2, byConv.size)
        assertEquals("conv-1", byConv[0].conversationId)
        assertEquals("glm-5.2", byConv[0].model)
        assertEquals("conv-3", byConv[1].conversationId)
        assertEquals("glm-4.7", byConv[1].model)
    }
}
