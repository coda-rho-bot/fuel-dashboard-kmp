package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.usage.UsageFieldError
import com.angussoftware.fueldashboard.usage.longFieldOr
import com.angussoftware.fueldashboard.usage.longFieldOrNull
import com.angussoftware.fueldashboard.database.UsageRepository
import com.angussoftware.fueldashboard.mcp.FuelMcpServer
import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FleetAgent
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.Provider
import com.angussoftware.fueldashboard.model.JunieBalanceData
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer as KtorServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded HTTP server — desktop app IS the orchestrator.
 * Serves fuel data to mobile devices on the same LAN.
 *
 * SECURITY: The server binds to [DEFAULT_HOST] (0.0.0.0) so it is reachable
 * from any device on the LAN.  All endpoints except `/health` require a Bearer
 * API key.  If LAN access is not needed, bind to `127.0.0.1` instead.
 */
class EmbeddedServer(
    private val repository: DecisionRepository? = null,
    private val agentRegistry: AgentRegistry? = null,
    private val usageRepository: UsageRepository? = null,
    private val port: Int = DEFAULT_PORT,
    private val host: String = DEFAULT_HOST,
    private val onProvidersChanged: () -> Unit = {},
    private val onImportSettings: ((SettingsSyncData) -> Unit)? = null,
    private val dashboardStateProvider: () -> com.angussoftware.fueldashboard.presentation.DashboardState? = { null },
    /** API key for auth. Defaults to the persisted key from ServerApiKeyStore. Tests inject a known key. */
    internal val apiKey: String = ServerApiKeyStore.loadOrCreate(Companion::generateApiKey),
    /** When false, the MCP streamable HTTP endpoint is not installed (for tests). */
    private val enableMcp: Boolean = true,
    /** Override Junie balance data (for tests). Defaults to reading from persisted settings. */
    private val junieBalanceProvider: () -> JunieBalanceData? = Companion::defaultJunieBalanceData,
) {
    companion object {
        const val DEFAULT_PORT = 8322
        /** Bind to all interfaces so LAN devices can reach the server. */
        const val DEFAULT_HOST = "0.0.0.0"
        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 1_000L

        private fun generateApiKey(): String = ByteArray(32)
            .also(SecureRandom()::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private fun defaultJunieBalanceData(): JunieBalanceData? {
            val balance = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull()
                ?: return null
            val license = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null }
            val lastChecked = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull()
            return JunieBalanceData(
                balance = balance,
                license = license,
                lastChecked = lastChecked,
            )
        }
    }

    private var server: KtorServer<*, *>? = null

    /** Public server URL (tunnel or LAN) — set by main.kt, used for sync data. */
    var serverUrl: String? = null

    /** Thread-safe registry of agents that self-registered via POST /agents/register or MCP register_agent. */
    internal val registeredAgents = ConcurrentHashMap<String, RegisteredAgent>()
    internal val agentIdCounter = AtomicLong(0)

    init {
        // Load persisted agents from SQLite on startup
        agentRegistry?.all()?.forEach { record ->
            registeredAgents[record.id] = RegisteredAgent(
                id = record.id,
                name = record.name,
                model = record.model,
                framework = record.framework,
                command = record.command,
                status = record.status,
                registeredAt = record.registeredAt,
            )
        }
    }

    @Volatile var fuelState: FuelResponse? = null
    @Volatile var agents: List<FleetAgent> = emptyList()
    @Volatile var alerts: List<String> = emptyList()

    /**
     * Merge registered agents (from POST /agents/register) with the ACP-discovered
     * agents (pushed from the ViewModel). Returns a unified list for GET /agents.
     */
    private fun mergedAgents(): List<FleetAgent> {
        val discovered = agents
        val registeredIds = discovered.map { it.agentId }.toMutableSet()

        val fromRegistry = registeredAgents.values.map { reg ->
            FleetAgent(
                agentId = reg.id,
                name = reg.name,
                currentModel = reg.model ?: "",
                lastTaskComplexity = "",
                fuelAllocation = 0,
                activeSubagents = 0,
            )
        }.filter { it.agentId !in registeredIds }

        return discovered + fromRegistry
    }

    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(CIO, host = host, port = port) { configureRouting(this) }
            server?.start(wait = false)
            println("[EmbeddedServer] Listening on http://$host:$port")
        } catch (e: java.net.BindException) {
            println("[EmbeddedServer] FAILED to bind port $port — already in use. Is another instance running? Error: ${e.message}")
            server = null
        } catch (e: Exception) {
            println("[EmbeddedServer] FAILED to start: ${e::class.simpleName}: ${e.message}")
            server = null
        }
    }

    fun stop() {
        server?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        server = null
    }

    /**
     * Returns registered agents as AcpAgentDisplay for the desktop UI.
     * Agents registered via MCP or HTTP POST appear here.
     */
    fun getRegisteredAgentsForDisplay(): List<AcpAgentDisplay> {
        return registeredAgents.values.map { agent ->
            AcpAgentDisplay(
                id = agent.id,
                name = agent.name,
                currentModel = agent.model,
                availableModels = emptyList(),
                currentMode = null,
                availableModes = emptyList(),
                status = agent.status,
                capabilities = agent.capabilities,
                framework = agent.framework,
                command = agent.command,
                registeredAt = if (agent.registeredAt > 0) agent.registeredAt else null,
            )
        }
    }

    /**
     * Removes a registered agent from both the in-memory map and SQLite.
     */
    fun deleteRegisteredAgent(id: String) {
        val removed = registeredAgents.remove(id)
        if (removed != null) {
            agentRegistry?.remove(id)
            println("[EmbeddedServer] Agent deleted: ${removed.name} ($id)")
        }
    }

    internal fun configureRouting(app: Application) = with(app) {
        // CORS: anyHost() is safe because credentials are NOT enabled.
        // MCP clients and browsers from any origin can connect, but they still
        // need a valid Bearer API key for every endpoint except /health.
        install(CORS) { anyHost() }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }

        // Global auth intercept — every endpoint except /health requires the API key.
        // CORS preflight (OPTIONS) requests are handled earlier in the pipeline and
        // never reach this intercept.
        intercept(ApplicationCallPipeline.Call) {
            if (call.request.path() == "/health") return@intercept
            if (!call.requireApiKey()) {
                finish()
            }
        }

        // Create the MCP server with shared access to the agent registry and fuel state
        if (enableMcp) {
            val mcpServer = FuelMcpServer(
                registeredAgents = registeredAgents,
                agentIdCounter = agentIdCounter,
                fuelStateProvider = { fuelState },
                agentRegistry = agentRegistry,
                onProvidersChanged = onProvidersChanged,
                serverUrlProvider = { serverUrl },
                serverApiKeyProvider = { apiKey },
                usageRepository = usageRepository,
                dashboardStateProvider = dashboardStateProvider,
            ).createServer()

            // MCP endpoint (Streamable HTTP at /mcp) — allows agents to self-register via MCP protocol
            // Note: mcpStreamableHttp auto-installs ContentNegotiation with McpJson, but since we already
            // installed it above, the SDK will log a warning and use our existing config (which is compatible
            // because we set explicitNulls = false and encodeDefaults = true).
            mcpStreamableHttp(enableDnsRebindingProtection = false) {
                mcpServer
            }
        }

        routing {
            get("/") {
                call.respond(ServiceInfo("fuel-dashboard", "2.0", listOf("GET /fuel", "GET /decisions", "GET /agents", "GET /alerts", "GET /sync", "POST /sync", "GET /dashboard", "GET /v1/usage", "POST /v1/usage (universal usage ingestion)", "GET /health (no auth)", "POST /agents/register", "POST /agents/{id}/state", "DELETE /agents/{id}", "POST /mcp (MCP Streamable HTTP)")))
            }

            get("/fuel") {
                // Serve the CURRENT provider adapter reports — not the stale
                // orchestrator state. ConnectedApi clients (other dashboard
                // instances, the mobile app) expect live provider gauges here.
                val state = dashboardStateProvider()
                val withJunie = state?.let { st ->
                    val providers = st.providerReports.values
                        .filter { it.available }
                        .associate { report ->
                            report.displayName to Provider(
                                name = report.displayName,
                                remainingPct = report.remainingPct,
                                available = report.available,
                                resetMs = report.resetsAt,
                            )
                        }
                    FuelResponse(
                        ts = st.lastUpdated,
                        providers = providers,
                        junie = junieBalanceProvider(),
                    )
                } ?: FuelResponse(junie = junieBalanceProvider())
                call.respond(withJunie)
            }

            get("/decisions") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                if (repository != null) {
                    val records = repository.getRecent(limit)
                    val decisions = records.map { r ->
                        Decision(
                            id = r.id,
                            agentId = r.agentId,
                            modelHandle = r.modelHandle,
                            provider = r.provider,
                            tier = r.tier,
                            complexity = r.complexity,
                            utilizationRatio = r.utilizationRatio,
                            headroom = r.headroom.toInt(),
                            reason = r.reason,
                            timestamp = r.timestamp,
                        )
                    }
                    call.respond(DecisionsResponse(decisions))
                } else {
                    call.respond(DecisionsResponse(emptyList()))
                }
            }

            get("/agents") { call.respond(AgentsResponse(mergedAgents())) }

            post("/agents/register") {
                val req = call.receive<RegisterAgentRequest>()
                val baseId = req.name.lowercase().replace("\\s+".toRegex(), "-")
                // Dedup by name — update existing instead of creating duplicate
                val existing = registeredAgents.values.find { it.name.equals(req.name, ignoreCase = true) }
                val id = existing?.id ?: baseId.ifBlank { "agent-${agentIdCounter.incrementAndGet()}" }
                val agent = RegisteredAgent(
                    id = id,
                    name = req.name,
                    model = req.model,
                    framework = req.framework,
                    command = req.command,
                    registeredAt = System.currentTimeMillis(),
                )
                registeredAgents[id] = agent
                // Persist to SQLite
                agentRegistry?.upsert(id, agent.name, agent.model, agent.framework, agent.command, agent.status)
                println("[EmbeddedServer] Agent registered: ${agent.name} ($id)")
                call.respond(RegisterAgentResponse("registered", id))
            }

            post("/agents/{id}/state") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing agent id"))
                val req = call.receive<UpdateAgentStateRequest>()
                val existing = registeredAgents[id]
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("agent not found: $id"))
                val updated = existing.copy(
                    model = req.model ?: existing.model,
                    status = req.status ?: existing.status,
                    capabilities = req.capabilities ?: existing.capabilities,
                )
                registeredAgents[id] = updated
                agentRegistry?.upsert(id, updated.name, updated.model, updated.framework, updated.command, updated.status)
                call.respond(StateUpdateResponse("updated"))
            }

            delete("/agents/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing agent id"))
                val removed = registeredAgents.remove(id)
                if (removed != null) {
                    agentRegistry?.remove(id)
                    println("[EmbeddedServer] Agent removed: ${removed.name} ($id)")
                    call.respond(StateUpdateResponse("removed"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("agent not found: $id"))
                }
            }

            get("/alerts") { call.respond(AlertsResponse(alerts)) }

            // Sync endpoint — returns base64 sync code for cross-device setup
            get("/sync") {
                if (!call.requireApiKey()) return@get
                val settings = FuelSettingsStore.loadMultiProvider()
                val agentSettings = AgentSettingsStore.load()
                // Canonical builder: real theme, junie, section orders,
                // usage sources, prefs. Hand-building this (the old way)
                // hardcoded theme defaults — importing such a code silently
                // reset the receiver's theme.
                val syncData = SettingsSyncData.from(
                    settings = settings,
                    agentSettings = agentSettings,
                    themeController = com.angussoftware.fueldashboard.settings.ThemeController,
                    serverUrl = serverUrl,
                    serverApiKey = apiKey,
                    junieBalance = com.angussoftware.fueldashboard.settings.loadStringSetting(
                        com.angussoftware.fueldashboard.settings.FuelSettingsKeys.JUNIE_BALANCE, "",
                    ).toDoubleOrNull(),
                    junieLicense = com.angussoftware.fueldashboard.settings.loadStringSetting(
                        com.angussoftware.fueldashboard.settings.FuelSettingsKeys.JUNIE_LICENSE, "",
                    ).ifBlank { null },
                    junieLastChecked = com.angussoftware.fueldashboard.settings.loadStringSetting(
                        com.angussoftware.fueldashboard.settings.FuelSettingsKeys.JUNIE_LAST_CHECKED, "",
                    ).toLongOrNull(),
                )
                call.respondText(
                    text = """{"sync_code":"${syncData.toCode()}","server_url":"${serverUrl ?: ""}"}""",
                    contentType = ContentType.Application.Json,
                )
            }

            // Apply sync code from another instance — POST /sync
            post("/sync") {
                if (!call.requireApiKey()) return@post
                val body = call.receive<Map<String, String>>()
                val code = body["sync_code"]
                if (code.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("sync_code is required"))
                    return@post
                }

                val syncData = SettingsSyncData.fromCode(code)
                if (syncData == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid sync code"))
                    return@post
                }

                // Full import path when wired (providers, agents, theme, junie,
                // section orders, usage sources, preferences) — one code path
                // with the app's importSyncedSettings. Falls back to the legacy
                // partial apply when no callback is provided.
                if (onImportSettings != null) {
                    onImportSettings.invoke(syncData)
                } else {
                    // Apply providers + add Remote Dashboard
                    val providers = syncData.providers.toMutableList()
                    syncData.serverUrl?.let { url ->
                        providers.removeAll { it.kind == com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API }
                        providers.add(
                            com.angussoftware.fueldashboard.model.ProviderConfig(
                                id = "synced-orchestrator",
                                kind = com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API,
                                apiKey = syncData.serverApiKey.orEmpty(),
                                displayName = "Remote Dashboard",
                                serverUrl = url,
                            ),
                        )
                    }
                    FuelSettingsStore.saveMultiProvider(
                        com.angussoftware.fueldashboard.model.MultiProviderSettings(providers = providers),
                    )
                    AgentSettingsStore.save(syncData.agentSettings)
                    syncData.serverApiKey?.takeIf { it.isNotBlank() }?.let { key ->
                        ServerApiKeyStore.save(key)
                    }
                    onProvidersChanged()
                }

                call.respondText(
                    text = kotlinx.serialization.json.buildJsonObject {
                        put("status", "synced")
                        put("providers_imported", syncData.providers.size)
                        put("agents_imported", syncData.agentSettings.agents.size)
                    }.toString(),
                    contentType = ContentType.Application.Json,
                )
            }

            // ── Universal usage API (agnostic contract) ─────────────────────
            // POST /v1/usage — any runtime, provider, or tool can report usage.
            // Schema aligns with OTel GenAI semconv: source→service.name,
            // model→gen_ai.request.model, tokens→gen_ai.usage.*.
            post("/v1/usage") {
                if (!call.requireApiKey()) return@post
                val body = call.receiveText()
                val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val source = json["source"]?.jsonPrimitive?.content
                val model = json["model"]?.jsonPrimitive?.content
                if (source.isNullOrBlank() || model.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("source and model are required"))
                    return@post
                }
                // Present-but-malformed numeric fields are a 400 naming the field —
                // never silently coerced to 0 (shared rule with MCP report_usage).
                val numbers = try {
                    usageNumbers(json)
                } catch (e: UsageFieldError) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "invalid field"))
                    return@post
                }
                val timestamp = numbers[0]
                val inputTokens = numbers[1]
                val outputTokens = numbers[2]
                val requestCount = numbers[3]

                usageRepository?.insert(
                    timestamp = timestamp,
                    source = source,
                    model = model,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    requestCount = requestCount,
                ) ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("usage storage unavailable"))
                    return@post
                }

                call.respondText(
                    text = kotlinx.serialization.json.buildJsonObject {
                        put("status", "recorded")
                        put("source", source)
                        put("model", model)
                    }.toString(),
                    contentType = ContentType.Application.Json,
                )
            }

            // GET /v1/usage — query recorded usage. Optional ?since=<epoch_ms> (default 24h).
            get("/v1/usage") {
                if (!call.requireApiKey()) return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                    ?: (epochMillisNow() - 24L * 3_600_000)
                val repo = usageRepository ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("usage storage unavailable"))
                    return@get
                }
                val bySource = repo.getBySourceSince(since)
                val byModel = repo.getByModelSince(since)

                val responseJson = kotlinx.serialization.json.buildJsonObject {
                    put("since", since)
                    put("by_source", kotlinx.serialization.json.buildJsonArray {
                        bySource.forEach { u ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("source", u.source)
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                                put("request_count", u.requestCount)
                            })
                        }
                    })
                    put("by_model", kotlinx.serialization.json.buildJsonArray {
                        byModel.forEach { u ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("model", u.model)
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                                put("request_count", u.requestCount)
                            })
                        }
                    })
                }.toString()
                call.respondText(text = responseJson, contentType = ContentType.Application.Json)
            }

            // Complete dashboard display state (everything the UI shows, no secrets)
            get("/dashboard") {
                val state = dashboardStateProvider()
                if (state == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("dashboard state unavailable"))
                } else {
                    val snapshot = com.angussoftware.fueldashboard.presentation.DashboardSnapshot.build(state)
                    call.respondText(snapshot.toString(), ContentType.Application.Json)
                }
            }

            // Lightweight health check — no auth required (for uptime monitors).
            get("/health") {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }
        }
    }

    private suspend fun ApplicationCall.requireApiKey(): Boolean {
        val error = bearerAuthorizationError(apiKey, request.headers[HttpHeaders.Authorization])
        if (error == null) return true

        respond(HttpStatusCode.Unauthorized, ErrorResponse(error))
        return false
    }

    private fun epochMillisNow(): Long = System.currentTimeMillis()

    /**
     * Parses the numeric usage-ingestion fields. Absent fields default;
     * present-but-malformed fields throw [UsageFieldError] (mapped to 400
     * by the caller) instead of silently coercing to 0.
     * Order: [timestamp, input_tokens, output_tokens, request_count].
     */
    private fun usageNumbers(json: kotlinx.serialization.json.JsonObject): LongArray = with(json) {
        longArrayOf(
            longFieldOrNull("timestamp") ?: epochMillisNow(),
            longFieldOr("input_tokens", 0L),
            longFieldOr("output_tokens", 0L),
            longFieldOr("request_count", 1L),
        )
    }
}

internal fun bearerAuthorizationError(expectedKey: String, authorizationHeader: String?): String? {
    // Constant-time key comparison — a plain == on the full header leaks
    // match progress via timing. MessageDigest.isEqual length-safe.
    val prefix = "Bearer "
    val ok = authorizationHeader != null &&
        authorizationHeader.startsWith(prefix) &&
        java.security.MessageDigest.isEqual(
            authorizationHeader.substring(prefix.length).encodeToByteArray(),
            expectedKey.encodeToByteArray(),
        )
    return if (ok) null else "Unauthorized: provide Authorization: Bearer <API key>."
}

/** Returns the LAN IP address for display in the UI. */
fun getLanUrl(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return "http://${addr.hostAddress}:${EmbeddedServer.DEFAULT_PORT}"
                }
            }
        }
        "http://localhost:${EmbeddedServer.DEFAULT_PORT}"
    } catch (e: Exception) {
        "http://localhost:${EmbeddedServer.DEFAULT_PORT}"
    }
}

// ── Registration request/response models ──────────────────────────────

@Serializable
private data class RegisterAgentRequest(
    val name: String,
    val model: String? = null,
    val framework: String? = null,
    val command: String? = null,
)

@Serializable
private data class RegisterAgentResponse(
    val status: String,
    @kotlinx.serialization.SerialName("agentId")
    val agentId: String,
)

@Serializable
private data class UpdateAgentStateRequest(
    val model: String? = null,
    val status: String? = null,
    val capabilities: List<String>? = null,
)

@Serializable
private data class StateUpdateResponse(val status: String)

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
internal data class RegisteredAgent(
    val id: String,
    val name: String,
    val model: String? = null,
    val framework: String? = null,
    val command: String? = null,
    val status: String = "registered",
    val capabilities: List<String> = emptyList(),
    val registeredAt: Long = 0,
)

// ── Existing private models ────────────────────────────────────────────

@Serializable private data class ServiceInfo(val service: String, val version: String, val endpoints: List<String>)
