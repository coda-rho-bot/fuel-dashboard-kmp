package com.angussoftware.fueldashboard.mcp

import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.server.RegisteredAgent
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class SafeProvider(
    val id: String,
    val kind: String,
    val name: String,
    val serverUrl: String,
)

internal data class ProviderRemoval(
    val settings: MultiProviderSettings,
    val removed: ProviderConfig?,
)

internal fun addProviderToSettings(
    settings: MultiProviderSettings,
    provider: ProviderConfig,
): MultiProviderSettings = settings.copy(providers = settings.providers + provider)

internal fun removeProviderFromSettings(
    settings: MultiProviderSettings,
    id: String?,
    name: String?,
): ProviderRemoval {
    val provider = if (!id.isNullOrBlank()) {
        settings.providers.find { it.id == id }
    } else {
        settings.providers.find {
            it.displayName.equals(name, ignoreCase = true) ||
                it.resolvedDisplayName().equals(name, ignoreCase = true)
        }
    }
    return if (provider == null) {
        ProviderRemoval(settings, null)
    } else {
        ProviderRemoval(
            settings = settings.copy(providers = settings.providers.filterNot { it.id == provider.id }),
            removed = provider,
        )
    }
}

internal fun safeProviders(settings: MultiProviderSettings): List<SafeProvider> = settings.providers.map {
    SafeProvider(
        id = it.id,
        kind = it.kind.name,
        name = it.displayName,
        serverUrl = it.serverUrl,
    )
}

/**
 * MCP server that exposes the Fuel Dashboard's agent registration and fuel state
 * via the standard Model Context Protocol. Agents can self-register, update their
 * model/status, and read fuel state through MCP tools and resources.
 *
 * Uses the same [registeredAgents] registry as the HTTP POST endpoints in [EmbeddedServer],
 * ensuring a single source of truth for agent state.
 */
internal class FuelMcpServer(
    private val registeredAgents: ConcurrentHashMap<String, RegisteredAgent>,
    private val agentIdCounter: AtomicLong,
    private val fuelStateProvider: () -> FuelResponse?,
    private val agentRegistry: AgentRegistry? = null,
    private val onProvidersChanged: () -> Unit = {},
    private val serverUrlProvider: () -> String? = { null },
    private val serverApiKeyProvider: () -> String = { "" },
    private val usageRepository: com.angussoftware.fueldashboard.database.UsageRepository? = null,
    private val dashboardStateProvider: () -> com.angussoftware.fueldashboard.presentation.DashboardState? = { null },
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    /**
     * Creates and configures the MCP [Server] with tools and resources.
     * Call this once and pass the result to `mcpStreamableHttp { server }`.
     */
    fun createServer(): Server = Server(
        serverInfo = Implementation(
            name = "fuel-dashboard",
            version = "2.0.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
            ),
        ),
    ) {
        getDashboardTool()
        getWasteTool()
        getFuelEventsTool()
        getAdviceTool()
        registerAgentTool()
        updateModelTool()
        updateStatusTool()
        addProviderTool()
        removeProviderTool()
        listProvidersTool()
        addOrchestratorTool()
        getSyncDataTool()
        applySyncDataTool()
        reportUsageTool()
        getUsageTool()
        currentFuelResource()
        recommendationResource()
    }

    // ── Tools ────────────────────────────────────────────────────────────

    /**
     * Tool: register_agent
     *
     * Agents call this to self-register with the Fuel Dashboard.
     * Returns a JSON response with the assigned agentId.
     */
    private fun Server.registerAgentTool() {
        addTool(
            name = "register_agent",
            description = "Agent self-registers with the Fuel Dashboard. " +
                "Returns a JSON object with 'status' and 'agentId'.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Human-readable agent name")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "Model handle (e.g., 'anthropic/claude-sonnet-5')")
                    })
                    put("framework", buildJsonObject {
                        put("type", "string")
                        put("description", "Agent framework (e.g., 'letta', 'crewai', 'autogen')")
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Command to start the agent process")
                    })
                },
            ),
        ) { request ->
            val args = request.arguments
            val name = args?.get("name")?.jsonPrimitive?.content ?: "unknown"
            val model = args?.get("model")?.jsonPrimitive?.content
            val framework = args?.get("framework")?.jsonPrimitive?.content
            val command = args?.get("command")?.jsonPrimitive?.content

            val baseId = name.lowercase().replace("\\s+".toRegex(), "-")
            // Check if already registered by name (case-insensitive) — update instead of duplicate
            val existing = registeredAgents.values.find { it.name.equals(name, ignoreCase = true) }
            val id = existing?.id ?: baseId.ifBlank { "agent-${agentIdCounter.incrementAndGet()}" }
            val agent = RegisteredAgent(
                id = id,
                name = name,
                model = model ?: existing?.model,
                framework = framework ?: existing?.framework,
                command = command ?: existing?.command,
                registeredAt = existing?.registeredAt ?: System.currentTimeMillis(),
            )
            registeredAgents[id] = agent
            // Persist to SQLite
            agentRegistry?.upsert(id, agent.name, agent.model, agent.framework, agent.command, agent.status)
            println("[MCP] Agent registered: ${agent.name} ($id)")

            val responseJson = buildJsonObject {
                put("status", "registered")
                put("agentId", id)
            }
            CallToolResult(content = listOf(TextContent(text = responseJson.toString())))
        }
    }

    /**
     * Tool: update_model
     *
     * Agents call this to report that they have switched to a different model.
     */
    private fun Server.updateModelTool() {
        addTool(
            name = "update_model",
            description = "Agent reports that it has switched to a different model. " +
                "Requires 'agentId' and 'model'. Returns a JSON status.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("agentId", buildJsonObject {
                        put("type", "string")
                        put("description", "The agent's ID (returned from register_agent)")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "New model handle (e.g., 'openai/gpt-4o')")
                    })
                },
            ),
        ) { request ->
            val args = request.arguments
            val agentId = args?.get("agentId")?.jsonPrimitive?.content
            val model = args?.get("model")?.jsonPrimitive?.content

            if (agentId == null || model == null) {
                CallToolResult(
                    content = listOf(TextContent(text = """{"error":"agentId and model are required"}""")),
                    isError = true,
                )
            } else {
                val existing = registeredAgents[agentId]
                if (existing != null) {
                    registeredAgents[agentId] = existing.copy(model = model)
                    println("[MCP] Agent $agentId model updated to: $model")
                    CallToolResult(content = listOf(TextContent(text = """{"status":"updated","agentId":"$agentId","model":"$model"}""")))
                } else {
                    CallToolResult(
                        content = listOf(TextContent(text = """{"error":"agent not found: $agentId"}""")),
                        isError = true,
                    )
                }
            }
        }
    }

    /**
     * Tool: update_status
     *
     * Agents call this to report their current operational status.
     */
    private fun Server.updateStatusTool() {
        addTool(
            name = "update_status",
            description = "Agent reports its current operational status. " +
                "Requires 'agentId' and 'status'. Returns a JSON status.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("agentId", buildJsonObject {
                        put("type", "string")
                        put("description", "The agent's ID (returned from register_agent)")
                    })
                    put("status", buildJsonObject {
                        put("type", "string")
                        put("description", "Current status (e.g., 'idle', 'working', 'waiting', 'error')")
                    })
                },
            ),
        ) { request ->
            val args = request.arguments
            val agentId = args?.get("agentId")?.jsonPrimitive?.content
            val status = args?.get("status")?.jsonPrimitive?.content

            if (agentId == null || status == null) {
                CallToolResult(
                    content = listOf(TextContent(text = """{"error":"agentId and status are required"}""")),
                    isError = true,
                )
            } else {
                val existing = registeredAgents[agentId]
                if (existing != null) {
                    registeredAgents[agentId] = existing.copy(status = status)
                    println("[MCP] Agent $agentId status updated to: $status")
                    CallToolResult(content = listOf(TextContent(text = """{"status":"updated","agentId":"$agentId","agentStatus":"$status"}""")))
                } else {
                    CallToolResult(
                        content = listOf(TextContent(text = """{"error":"agent not found: $agentId"}""")),
                        isError = true,
                    )
                }
            }
        }
    }

    /**
     * Tool: add_provider
     *
     * Adds an LLM provider to the persisted multi-provider settings.
     */
    private fun Server.addProviderTool() {
        addTool(
            name = "add_provider",
            description = "Adds an LLM provider to the Fuel Dashboard. Requires 'kind' and 'api_key'.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("description", "Provider kind (e.g., 'OPENAI', 'ANTHROPIC', or 'ZAI')")
                    })
                    put("api_key", buildJsonObject {
                        put("type", "string")
                        put("description", "Provider API key")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional display name")
                    })
                    put("server_url", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional provider server URL override")
                    })
                },
                required = listOf("kind", "api_key"),
            ),
        ) { request ->
            val args = request.arguments
            val kindName = args?.get("kind")?.jsonPrimitive?.content
            val apiKey = args?.get("api_key")?.jsonPrimitive?.content
            if (kindName.isNullOrBlank() || apiKey.isNullOrBlank()) {
                return@addTool errorResult("kind and api_key are required")
            }

            val kind = parseProviderKind(kindName)
                ?: return@addTool errorResult("unknown provider kind: $kindName")
            val provider = ProviderConfig(
                id = FuelSettingsStore.generateProviderId(),
                kind = kind,
                apiKey = apiKey,
                displayName = args["name"]?.jsonPrimitive?.content ?: "",
                serverUrl = args["server_url"]?.jsonPrimitive?.content ?: "",
            )

            runCatching {
                val current = FuelSettingsStore.loadMultiProvider()
                FuelSettingsStore.saveMultiProvider(
                    addProviderToSettings(current, provider),
                )
                onProvidersChanged()
            }.fold(
                onSuccess = {
                    successResult("provider added: ${provider.id}")
                },
                onFailure = { errorResult("failed to add provider: ${it.message ?: "unknown error"}") },
            )
        }
    }

    /**
     * Tool: remove_provider
     *
     * Removes one provider by ID or display name.
     */
    private fun Server.removeProviderTool() {
        addTool(
            name = "remove_provider",
            description = "Removes a configured provider by 'id' or 'name'. ID takes precedence when both are provided.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Provider ID")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Provider display name")
                    })
                },
            ),
        ) { request ->
            val args = request.arguments
            val id = args?.get("id")?.jsonPrimitive?.content
            val name = args?.get("name")?.jsonPrimitive?.content
            if (id.isNullOrBlank() && name.isNullOrBlank()) {
                return@addTool errorResult("id or name is required")
            }

            runCatching {
                val current = FuelSettingsStore.loadMultiProvider()
                val removal = removeProviderFromSettings(current, id, name)
                if (removal.removed == null) {
                    errorResult("provider not found")
                } else {
                    FuelSettingsStore.saveMultiProvider(removal.settings)
                    onProvidersChanged()
                    successResult("provider removed: ${removal.removed.id}")
                }
            }.getOrElse { errorResult("failed to remove provider: ${it.message ?: "unknown error"}") }
        }
    }

    /**
     * Tool: list_providers
     *
     * Lists configured providers without exposing API keys.
     */
    private fun Server.listProvidersTool() {
        addTool(
            name = "list_providers",
            description = "Lists configured providers without API keys.",
        ) {
            val providers = safeProviders(FuelSettingsStore.loadMultiProvider())
            val response = buildJsonObject {
                put("providers", buildJsonArray {
                    providers.forEach { provider ->
                        add(buildJsonObject {
                            put("id", provider.id)
                            put("kind", provider.kind)
                            put("name", provider.name)
                            put("server_url", provider.serverUrl)
                        })
                    }
                })
            }
            CallToolResult(content = listOf(TextContent(text = response.toString())))
        }
    }

    /**
     * Tool: add_orchestrator
     *
     * Adds a remote Fuel Dashboard server connection.
     */
    private fun Server.addOrchestratorTool() {
        addTool(
            name = "add_orchestrator",
            description = "Adds a remote Fuel Dashboard server connection. Requires 'url'. " +
                "Optional 'api_key' for the remote server's auth.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Remote Fuel Dashboard server URL")
                    })
                    put("api_key", buildJsonObject {
                        put("type", "string")
                        put("description", "API key for the remote dashboard's server (required if the remote dashboard has auth enabled)")
                    })
                },
                required = listOf("url"),
            ),
        ) { request ->
            val url = request.arguments?.get("url")?.jsonPrimitive?.content
            if (url.isNullOrBlank()) {
                return@addTool errorResult("url is required")
            }
            val apiKey = request.arguments?.get("api_key")?.jsonPrimitive?.content ?: ""

            val provider = ProviderConfig(
                id = FuelSettingsStore.generateProviderId(),
                kind = ProviderKind.CONNECTED_API,
                serverUrl = url,
                apiKey = apiKey,
                displayName = "Remote Dashboard",
            )
            runCatching {
                val current = FuelSettingsStore.loadMultiProvider()
                FuelSettingsStore.saveMultiProvider(
                    addProviderToSettings(current, provider),
                )
                onProvidersChanged()
            }.fold(
                onSuccess = {
                    successResult("remote dashboard added: ${provider.id}")
                },
                onFailure = { errorResult("failed to add remote dashboard: ${it.message ?: "unknown error"}") },
            )
        }
    }

    /**
     * Tool: get_sync_data
     *
     * Returns the full sync payload (server URL, API key, all provider configs,
     * agent settings) as a base64-encoded text code. This can be pasted into
     * another Fuel Dashboard instance's "Import Sync Code" field to instantly
     * replicate all settings including API keys.
     *
     * Useful for agents to programmatically sync dashboard instances across
     * machines without manual QR scanning.
     */
    private fun Server.getSyncDataTool() {
        addTool(
            name = "get_sync_data",
            description = "Returns a base64 sync code containing server URL, API key, " +
                "all provider configs, and agent settings. Paste into another Fuel " +
                "Dashboard instance to replicate settings. Returns JSON with 'sync_code' " +
                "and 'server_url'.",
        ) {
            val settings = FuelSettingsStore.loadMultiProvider()
            val agentSettings = AgentSettingsStore.load()
            val apiKey = serverApiKeyProvider()
            val url = serverUrlProvider()

            // Canonical builder: real theme, junie, section orders, usage
            // sources, prefs — hand-building hardcoded theme defaults that
            // silently reset the receiver's theme on import.
            val syncData = SettingsSyncData.from(
                settings = settings,
                agentSettings = agentSettings,
                themeController = com.angussoftware.fueldashboard.settings.ThemeController,
                serverUrl = url,
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
            val syncCode = syncData.toCode()

            val response = buildJsonObject {
                put("sync_code", syncCode)
                put("server_url", url ?: "")
                put("provider_count", settings.providers.size)
            }
            CallToolResult(content = listOf(TextContent(text = response.toString())))
        }
    }

    /**
     * Tool: apply_sync_data
     *
     * Applies a base64 sync code (from another Fuel Dashboard's get_sync_data)
     * to this instance. Imports all providers, agent settings, server URL, and
     * API key. Also adds a Remote Dashboard provider pointing at the source
     * instance.
     */
    private fun Server.applySyncDataTool() {
        addTool(
            name = "apply_sync_data",
            description = "Applies a base64 sync code from another Fuel Dashboard instance. " +
                "Imports providers, agent settings, and server connection. Requires 'sync_code'.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sync_code", buildJsonObject {
                        put("type", "string")
                        put("description", "Base64 sync code from another instance's get_sync_data")
                    })
                },
                required = listOf("sync_code"),
            ),
        ) { request ->
            val code = request.arguments?.get("sync_code")?.jsonPrimitive?.content
            if (code.isNullOrBlank()) {
                return@addTool errorResult("sync_code is required")
            }

            val syncData = SettingsSyncData.fromCode(code)
                ?: return@addTool errorResult("invalid sync code")

            // Apply providers — merge synced providers and add Remote Dashboard
            val providers = syncData.providers.toMutableList()
            syncData.serverUrl?.let { url ->
                providers.removeAll { it.kind == ProviderKind.CONNECTED_API }
                providers.add(
                    ProviderConfig(
                        id = "synced-orchestrator",
                        kind = ProviderKind.CONNECTED_API,
                        apiKey = syncData.serverApiKey.orEmpty(),
                        displayName = "Remote Dashboard",
                        serverUrl = url,
                    ),
                )
            }
            FuelSettingsStore.saveMultiProvider(MultiProviderSettings(providers = providers))

            // Apply agent settings
            AgentSettingsStore.save(syncData.agentSettings)

            // Apply server API key locally
            syncData.serverApiKey?.takeIf { it.isNotBlank() }?.let { key ->
                ServerApiKeyStore.save(key)
            }

            onProvidersChanged()

            val response = buildJsonObject {
                put("status", "synced")
                put("providers_imported", syncData.providers.size)
                put("agents_imported", syncData.agentSettings.agents.size)
                put("server_url", syncData.serverUrl ?: "")
            }
            CallToolResult(content = listOf(TextContent(text = response.toString())))
        }
    }

    /**
     * Tool: report_usage
     *
     * Universal usage reporting — any agent, runtime, or tool reports LLM
     * consumption. Contract aligns with OTel GenAI semantic conventions
     * (source→service.name, model→gen_ai.request.model, tokens→gen_ai.usage.*).
     * Dual-registers with the HTTP POST /v1/usage endpoint (same storage).
     */
    private fun Server.reportUsageTool() {
        addTool(
            name = "report_usage",
            description = "Report LLM usage (tokens consumed). Any agent/runtime/tool calls this " +
                "after completing work. Requires 'source' (your name/id) and 'model'. " +
                "input_tokens/output_tokens are the consumed counts for the period.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "Identity of the consumer (agent name, runtime, tool)")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "Model handle used (e.g., 'glm-5.2')")
                    })
                    put("input_tokens", buildJsonObject {
                        put("type", "number")
                        put("description", "Prompt/input tokens consumed")
                    })
                    put("output_tokens", buildJsonObject {
                        put("type", "number")
                        put("description", "Completion/output tokens consumed")
                    })
                    put("request_count", buildJsonObject {
                        put("type", "number")
                        put("description", "Number of requests (default 1)")
                    })
                    put("timestamp", buildJsonObject {
                        put("type", "number")
                        put("description", "Epoch ms of the usage (default now)")
                    })
                },
                required = listOf("source", "model"),
            ),
        ) { request ->
            val args = request.arguments
            val source = args?.get("source")?.jsonPrimitive?.content
            val model = args?.get("model")?.jsonPrimitive?.content
            if (source.isNullOrBlank() || model.isNullOrBlank()) {
                return@addTool errorResult("source and model are required")
            }
            val inputTokens = args?.get("input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val outputTokens = args?.get("output_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val requestCount = args?.get("request_count")?.jsonPrimitive?.content?.toLongOrNull() ?: 1L
            val timestamp = args?.get("timestamp")?.jsonPrimitive?.content?.toLongOrNull()
                ?: com.angussoftware.fueldashboard.util.epochMillis()

            val repo = usageRepository
                ?: return@addTool errorResult("usage storage unavailable")

            repo.insert(
                timestamp = timestamp,
                source = source,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                requestCount = requestCount,
            )

            successResult("usage recorded: $source/$model ${inputTokens}in/${outputTokens}out")
        }
    }

    /**
     * Tool: get_usage
     *
     * Query recorded usage by source and model. Default window: last 24h.
     */
    private fun Server.getUsageTool() {
        addTool(
            name = "get_usage",
            description = "Query recorded usage totals by source (agent/runtime) and model. " +
                "Optional 'since_hours' (default 24). Returns per-source and per-model breakdowns.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("since_hours", buildJsonObject {
                        put("type", "number")
                        put("description", "Lookback window in hours (default 24)")
                    })
                },
            ),
        ) { request ->
            val repo = usageRepository
                ?: return@addTool errorResult("usage storage unavailable")
            val sinceHours = request.arguments?.get("since_hours")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 24.0
            val since = com.angussoftware.fueldashboard.util.epochMillis() - (sinceHours * 3_600_000).toLong()

            val response = buildJsonObject {
                put("by_source", buildJsonArray {
                    repo.getBySourceSince(since).forEach { u ->
                        add(buildJsonObject {
                            put("source", u.source)
                            put("input_tokens", u.inputTokens)
                            put("output_tokens", u.outputTokens)
                            put("request_count", u.requestCount)
                        })
                    }
                })
                put("by_model", buildJsonArray {
                    repo.getByModelSince(since).forEach { u ->
                        add(buildJsonObject {
                            put("model", u.model)
                            put("input_tokens", u.inputTokens)
                            put("output_tokens", u.outputTokens)
                            put("request_count", u.requestCount)
                        })
                    }
                })
            }
            CallToolResult(content = listOf(TextContent(text = response.toString())))
        }
    }

    private fun parseProviderKind(value: String): ProviderKind? = runCatching {
        ProviderKind.valueOf(value.trim().uppercase().replace('-', '_').replace(' ', '_'))
    }.getOrNull()


    private fun successResult(message: String): CallToolResult = CallToolResult(
        content = listOf(
            TextContent(
                text = buildJsonObject {
                    put("status", "success")
                    put("message", message)
                }.toString(),
            ),
        ),
    )

    /**
     * Tool: get_dashboard
     *
     * The complete dashboard display state — every piece of information the UI
     * shows: providers (levels, resets, errors), fuel projection, advisor
     * advice, all metered usage breakdowns (source/model/conversation/agent×model,
     * 24h+7d), wasted quota per provider, fuel events timeline, model drain
     * rates, agents, ingestion status. No secrets (API keys excluded).
     */
    private fun Server.getDashboardTool() {
        addTool(
            name = "get_dashboard",
            description = "Get the COMPLETE dashboard state: providers, fuel projection, advisor advice, " +
                "metered usage (all breakdowns, 24h+7d), wasted quota, fuel events, model drain rates, " +
                "agents, ingestion status. Everything the UI displays, no secrets.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { _ ->
            val state = dashboardStateProvider()
                ?: return@addTool errorResult("dashboard state unavailable")
            val snapshot = com.angussoftware.fueldashboard.presentation.DashboardSnapshot.build(state)
            CallToolResult(content = listOf(TextContent(text = snapshot.toString())))
        }
    }

    /**
     * Tool: get_waste — expired-quota waste per provider (unused capacity at
     * each window expiry), with daily rollups and observed/estimated counts.
     */
    private fun Server.getWasteTool() {
        addTool(
            name = "get_waste",
            description = "Wasted quota per provider: how much quota expired unused at each window " +
                "expiry (provider-specific window mechanics), daily averages, observed vs estimated windows.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { _ ->
            val state = dashboardStateProvider()
                ?: return@addTool errorResult("dashboard state unavailable")
            if (state.wasteByProvider.isEmpty()) {
                return@addTool CallToolResult(content = listOf(TextContent(text = "{\"waste\": {}}")))
            }
            val waste = com.angussoftware.fueldashboard.presentation.DashboardSnapshot.build(state)["waste"] ?: kotlinx.serialization.json.JsonObject(emptyMap())
            CallToolResult(content = listOf(TextContent(text = waste.toString())))
        }
    }

    /**
     * Tool: get_fuel_events — the deduplicated fuel event timeline (drops,
     * model switches, recommendation changes), newest first.
     */
    private fun Server.getFuelEventsTool() {
        addTool(
            name = "get_fuel_events",
            description = "Fuel event timeline: significant gauge drops (burst-aggregated), agent model " +
                "switches, and recommendation changes — newest first, deduplicated.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { _ ->
            val state = dashboardStateProvider()
                ?: return@addTool errorResult("dashboard state unavailable")
            val events = com.angussoftware.fueldashboard.presentation.DashboardSnapshot.build(state)["fuel_events"] ?: kotlinx.serialization.json.JsonObject(emptyMap())
            CallToolResult(content = listOf(TextContent(text = events.toString())))
        }
    }

    /**
     * Tool: get_advice — the Fuel Advisor's current regime-aware advice state
     * (surplus/healthy/at-risk/persistent-pressure) with routine-consumer details.
     */
    private fun Server.getAdviceTool() {
        addTool(
            name = "get_advice",
            description = "Current fuel advisor advice: quota regime (exhaustion history), window " +
                "projection, and — when actionable — which routine work to move to a cheaper model " +
                "with projected savings.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { _ ->
            val state = dashboardStateProvider()
                ?: return@addTool errorResult("dashboard state unavailable")
            if (state.fuelAdvice == null) {
                return@addTool CallToolResult(content = listOf(TextContent(text = "{\"advisor\": null, \"message\": \"advisor data not yet computed\"}")))
            }
            val advice = com.angussoftware.fueldashboard.presentation.DashboardSnapshot.build(state)["advisor"] ?: kotlinx.serialization.json.JsonNull
            CallToolResult(content = listOf(TextContent(text = advice.toString())))
        }
    }

    private fun errorResult(message: String): CallToolResult =
        CallToolResult(
            content = listOf(
                TextContent(
                    text = buildJsonObject { put("error", message) }.toString(),
                ),
            ),
            isError = true,
        )

    // ── Resources ─────────────────────────────────────────────────────────

    /**
     * Resource: fuel://current
     *
     * Returns the current fuel state as JSON.
     */
    private fun Server.currentFuelResource() {
        addResource(
            uri = "fuel://current",
            name = "Current Fuel State",
            description = "Current fuel levels, costs, and limits for all configured providers",
            mimeType = "application/json",
        ) { request ->
            val state = fuelStateProvider()
            val text = if (state != null) {
                json.encodeToString(state)
            } else {
                """{"providers":{}}"""
            }
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = text,
                        uri = request.uri,
                        mimeType = "application/json",
                    ),
                ),
            )
        }
    }

    /**
     * Resource: fuel://recommendation
     *
     * Returns the current recommended model based on fuel levels.
     */
    private fun Server.recommendationResource() {
        addResource(
            uri = "fuel://recommendation",
            name = "Model Recommendation",
            description = "Current model recommendation based on fuel levels, cost optimization, and headroom",
            mimeType = "application/json",
        ) { request ->
            val state = fuelStateProvider()
            val text = if (state != null) {
                buildJsonObject {
                    put("recommended_model", state.recommendedModel)
                    put("burn_rate_pct_per_hr", state.burnRatePctPerHr)
                    put("surplus_alert", state.surplusAlert)
                }.toString()
            } else {
                """{"recommended_model":"","burn_rate_pct_per_hr":0.0,"surplus_alert":false}"""
            }
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = text,
                        uri = request.uri,
                        mimeType = "application/json",
                    ),
                ),
            )
        }
    }
}
