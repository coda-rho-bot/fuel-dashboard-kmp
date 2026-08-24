package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.presentation.DashboardState
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the embedded HTTP server endpoints.
 *
 * Uses Ktor's testApplication to spin up the routing in-memory without
 * binding a real port. MCP is disabled (enableMcp=false) to isolate
 * REST endpoint behavior from the MCP streamable HTTP transport.
 */
class EmbeddedServerEndpointsTest {

    private fun createServer(
        apiKey: String = "test-api-key",
        fuelState: FuelResponse? = null,
        dashboardState: DashboardState? = null,
        onImportSettings: ((SettingsSyncData) -> Unit)? = null,
    ): EmbeddedServer {
        return EmbeddedServer(
            apiKey = apiKey,
            enableMcp = false,
            onImportSettings = onImportSettings,
            dashboardStateProvider = { dashboardState },
        ).also {
            it.fuelState = fuelState
        }
    }

    // ── Health (no auth) ──────────────────────────────────────────────

    @Test
    fun healthEndpointReturnsOkWithoutAuth() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("ok"), body)
        }
    }

    // ── Auth intercept ────────────────────────────────────────────────

    @Test
    fun endpointsWithoutAuthReturn401() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/fuel").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun endpointsWithWrongKeyReturn401() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/fuel") {
            header(HttpHeaders.Authorization, "Bearer wrong-key")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun endpointsWithCorrectKeyAreAllowed() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/fuel") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    // ── Service info (GET /) ──────────────────────────────────────────

    @Test
    fun rootReturnsServiceInfo() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("fuel-dashboard"), body)
            // Version intentionally not pinned — API version bumps shouldn't break this suite.
        }
    }

    // ── Fuel (GET /fuel) ─────────────────────────────────────────────

    @Test
    fun fuelEndpointReturnsFuelState() = testApplication {
        val fuel = FuelResponse(burnRatePctPerHr = 2.5, recommendedModel = "glm-5.2")
        val server = createServer(fuelState = fuel)
        application { server.configureRouting(this) }

        client.get("/fuel") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            // snake_case wire format (not camelCase)
            assertTrue(body.contains("burn_rate_pct_per_hr"), "wire format should be snake_case: $body")
            assertTrue(body.contains("2.5"), body)
            assertTrue(body.contains("recommended_model"), "wire format should be snake_case: $body")
            assertTrue(body.contains("glm-5.2"), body)
        }
    }

    @Test
    fun fuelEndpointReturnsEmptyResponseWhenNoState() = testApplication {
        val server = createServer(fuelState = null)
        application { server.configureRouting(this) }

        client.get("/fuel") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    // ── Agents (GET /agents, POST /agents/register) ─────────────────

    @Test
    fun agentsEndpointReturnsEmptyListInitially() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/agents") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("agents"), body)
        }
    }

    @Test
    fun registerAgentCreatesEntryAndReturnsId() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/agents/register") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Agent","model":"glm-5.2"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("registered"), body)
            assertTrue(body.contains("test-agent"), "agentId should be slugified name: $body")
        }
    }

    @Test
    fun registerAgentDedupesByName() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        // First registration
        client.post("/agents/register") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Agent","model":"glm-5.2"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        // Second registration with same name — should update, not create duplicate
        client.post("/agents/register") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Agent","model":"glm-4.7"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("test-agent"), "should reuse same ID: $body")
        }

        // Verify only one agent in the list
        client.get("/agents") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            val body: String = body()
            // Count occurrences of "test-agent" — should be exactly 1
            assertEquals(1, body.split("test-agent").size - 1, "should be exactly 1 agent: $body")
        }
    }

    // ── Agent state update (POST /agents/{id}/state) ─────────────────

    @Test
    fun updateAgentStateReturns404ForUnknownAgent() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/agents/nonexistent/state") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun updateAgentStateUpdatesRegisteredAgent() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        // Register first
        client.post("/agents/register") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Agent","model":"glm-5.2"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        // Update state
        client.post("/agents/test-agent/state") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active","model":"glm-4.7"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("updated"), body)
        }
    }

    // ── Usage API (POST /v1/usage, GET /v1/usage) ───────────────────

    @Test
    fun postUsageRejectsMissingSourceAndModel() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"input_tokens":100}""")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val body: String = body()
            assertTrue(body.contains("source and model are required"), body)
        }
    }

    @Test
    fun postUsageRejectsMalformedTokensWith400() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        // Present-but-invalid numeric field must 400 naming the field —
        // never silently recorded as 0 tokens.
        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"letta","model":"glm-5.2","input_tokens":"abc"}""")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val body: String = body()
            assertTrue(body.contains("input_tokens"), body)
        }
    }

    @Test
    fun postUsageRejectsObjectTokensWith400() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"letta","model":"glm-5.2","output_tokens":{"n":5}}""")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val body: String = body()
            assertTrue(body.contains("output_tokens"), body)
        }
    }

    @Test
    fun postUsageRejectsMalformedTimestampWith400() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"letta","model":"glm-5.2","timestamp":"yesterday"}""")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val body: String = body()
            assertTrue(body.contains("timestamp"), body)
        }
    }

    @Test
    fun postUsageAcceptsAbsentOptionalFields() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        // All optional numeric fields absent → defaults apply (0/0/1) and the
        // request proceeds to the repository stage (503 here = no repo wired).
        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"letta","model":"glm-5.2"}""")
        }.apply {
            assertEquals(HttpStatusCode.ServiceUnavailable, status)
        }
    }

    @Test
    fun postUsageWithoutRepositoryReturns503() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"letta","model":"glm-5.2","input_tokens":100,"output_tokens":50}""")
        }.apply {
            assertEquals(HttpStatusCode.ServiceUnavailable, status)
        }
    }

    @Test
    fun getUsageWithoutRepositoryReturns503() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.ServiceUnavailable, status)
        }
    }

    // ── Dashboard (GET /dashboard) ──────────────────────────────────

    @Test
    fun dashboardEndpointReturns503WhenNoState() = testApplication {
        val server = createServer(dashboardState = null)
        application { server.configureRouting(this) }

        client.get("/dashboard") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.ServiceUnavailable, status)
        }
    }

    @Test
    fun dashboardEndpointReturnsSnapshotWhenStateAvailable() = testApplication {
        val state = DashboardState(lastUpdated = 1_000L)
        val server = createServer(dashboardState = state)
        application { server.configureRouting(this) }

        client.get("/dashboard") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("providers"), "snapshot should contain providers: $body")
        }
    }

    // ── Sync (GET /sync) ────────────────────────────────────────────

    @Test
    fun syncEndpointReturnsSyncCode() = testApplication {
        val server = createServer()
        server.serverUrl = "http://192.168.1.100:8322"
        application { server.configureRouting(this) }

        client.get("/sync") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("sync_code"), "should contain sync_code: $body")
            assertTrue(body.contains("server_url"), "should contain server_url: $body")
            assertTrue(body.contains("192.168.1.100"), "should contain the server URL: $body")
        }
    }

    // ── Alerts (GET /alerts) ────────────────────────────────────────

    @Test
    fun alertsEndpointReturnsEmptyList() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/alerts") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("alerts"), body)
        }
    }

    // ── Decisions (GET /decisions) ──────────────────────────────────

    @Test
    fun decisionsEndpointReturnsEmptyListWithoutRepository() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/decisions") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body: String = body()
            assertTrue(body.contains("decisions"), body)
        }
    }
}
