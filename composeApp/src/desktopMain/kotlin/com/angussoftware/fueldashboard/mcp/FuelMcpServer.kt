package com.angussoftware.fueldashboard.mcp

import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.server.RegisteredAgent
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
        registerAgentTool()
        updateModelTool()
        updateStatusTool()
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
            val id = if (baseId.isNotBlank() && !registeredAgents.containsKey(baseId)) {
                baseId
            } else {
                "agent-${agentIdCounter.incrementAndGet()}"
            }
            val agent = RegisteredAgent(
                id = id,
                name = name,
                model = model,
                framework = framework,
                command = command,
                registeredAt = System.currentTimeMillis(),
            )
            registeredAgents[id] = agent
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
