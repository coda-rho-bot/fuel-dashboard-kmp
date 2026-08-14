package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.database.UsageRepository
import com.angussoftware.fueldashboard.mcp.FuelMcpServer
import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FleetAgent
import com.angussoftware.fueldashboard.model.FuelResponse
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
) {
    companion object {
        const val DEFAULT_PORT = 8322
        /** Bind to all interfaces so LAN devices can reach the server. */
        const val DEFAULT_HOST = "0.0.0.0"
        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 1_000L
    }

    private var server: KtorServer<*, *>? = null
    private val apiKey = ServerApiKeyStore.loadOrCreate(::generateApiKey)

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
        server = embeddedServer(CIO, host = host, port = port) { configureRouting() }
        server?.start(wait = false)
        println("[EmbeddedServer] Listening on http://$host:$port")
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

    /**
     * Reads the last-known Junie balance from persisted settings.
     * Returns null if no balance has ever been checked.
     */
    private fun junieBalanceData(): JunieBalanceData? {
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

    private fun Application.configureRouting() {
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
        val mcpServer = FuelMcpServer(
            registeredAgents = registeredAgents,
            agentIdCounter = agentIdCounter,
            fuelStateProvider = { fuelState },
            agentRegistry = agentRegistry,
            onProvidersChanged = onProvidersChanged,
            serverUrlProvider = { serverUrl },
            serverApiKeyProvider = { apiKey },
            usageRepository = usageRepository,
        ).createServer()

        routing {
            get("/") {
                call.respond(ServiceInfo("fuel-dashboard", "2.0", listOf("GET /fuel", "GET /decisions", "GET /agents", "GET /alerts", "GET /sync", "POST /sync", "GET /health (no auth)", "POST /agents/register", "POST /agents/{id}/state", "DELETE /agents/{id}", "POST /mcp (MCP Streamable HTTP)")))
            }

            get("/fuel") {
                val state = fuelState
                val withJunie = state?.copy(junie = junieBalanceData())
                    ?: FuelResponse(junie = junieBalanceData())
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
                registeredAgents[id] = existing.copy(
                    model = req.model ?: existing.model,
                    status = req.status ?: existing.status,
                    capabilities = req.capabilities ?: existing.capabilities,
                )
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
                val syncData = SettingsSyncData(
                    providers = settings.providers,
                    themeMode = "SYSTEM",
                    lightColorTheme = "DEFAULT",
                    darkColorTheme = "DEFAULT",
                    serverUrl = serverUrl,
                    serverApiKey = apiKey,
                    agentSettings = agentSettings,
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

                call.respondText(
                    text = """{"status":"synced","providers_imported":${syncData.providers.size},"agents_imported":${syncData.agentSettings.agents.size}}""",
                    contentType = ContentType.Application.Json,
                )
            }

            // ── Universal usage API (agnostic contract) ─────────────────────
            // POST /v1/usage — any runtime, provider, or tool can report usage.
            // Schema aligns with OTel GenAI semconv: source→service.name,
            // model→gen_ai.request.model, tokens→gen_ai.usage.*.
            post("/v1/usage") {
                if (!call.requireApiKey()) return@post
                val body = call.receive<io.ktor.http.content.TextContent>().text
                val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val source = json["source"]?.jsonPrimitive?.content
                val model = json["model"]?.jsonPrimitive?.content
                if (source.isNullOrBlank() || model.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("source and model are required"))
                    return@post
                }
                val timestamp = json["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: epochMillisNow()
                val inputTokens = json["input_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val outputTokens = json["output_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val requestCount = json["request_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L

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
                    text = """{"status":"recorded","source":"$source","model":"$model"}""",
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

            // Lightweight health check — no auth required (for uptime monitors).
            get("/health") {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }
        }

        // MCP endpoint (Streamable HTTP at /mcp) — allows agents to self-register via MCP protocol
        // Note: mcpStreamableHttp auto-installs ContentNegotiation with McpJson, but since we already
        // installed it above, the SDK will log a warning and use our existing config (which is compatible
        // because we set explicitNulls = false and encodeDefaults = true).
        mcpStreamableHttp(enableDnsRebindingProtection = false) {
            mcpServer
        }
    }

    private suspend fun ApplicationCall.requireApiKey(): Boolean {
        val error = bearerAuthorizationError(apiKey, request.headers[HttpHeaders.Authorization])
        if (error == null) return true

        respond(HttpStatusCode.Unauthorized, ErrorResponse(error))
        return false
    }

    private fun generateApiKey(): String = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun epochMillisNow(): Long = System.currentTimeMillis()
}

internal fun bearerAuthorizationError(expectedKey: String, authorizationHeader: String?): String? {
    return if (authorizationHeader == "Bearer $expectedKey") {
        null
    } else {
        "Unauthorized: provide Authorization: Bearer <API key>."
    }
}

/** Returns the LAN IP address for display in the UI. */
fun getLanUrl(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return "http://${addr.hostAddress}:8322"
                }
            }
        }
        "http://localhost:8322"
    } catch (e: Exception) {
        "http://localhost:8322"
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
