package com.angussoftware.fueldashboard.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.GlobalElicitationHandler
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Manages ACP (Agent Client Protocol) connections to monitor AI agents.
 *
 * The manager spawns agent processes (e.g., `letta-acp`) and communicates
 * via JSON-RPC 2.0 over stdio using the acp-kotlin SDK (v0.24.0).
 *
 * Key operations:
 * - [startMonitoring]: Spawn agent processes and read their capabilities/model info
 * - [stopMonitoring]: Kill all processes and clean up
 * - [setModel]: Change an agent's model via ACP session API
 *
 * Agent info is reported via [agents] StateFlow, which updates as agents
 * connect, report their config, or disconnect.
 *
 * Usage:
 * ```kotlin
 * val manager = AcpAgentManager()
 * manager.startMonitoring(AcpAgentConfig.defaultFleet())
 * // Observe manager.agents for updates
 * manager.stopMonitoring() // on shutdown
 * ```
 */
@Suppress("UnstableApiUsage")
class AcpAgentManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _agents = MutableStateFlow<List<AcpAgentInfo>>(emptyList())
    val agents: StateFlow<List<AcpAgentInfo>> = _agents.asStateFlow()

    /** Active agent connections: agentId -> AcpConnection */
    private val connections = mutableMapOf<String, AcpConnection>()

    @Volatile
    private var monitoring = false

    /**
     * Start monitoring a list of ACP agents.
     *
     * For each agent config, spawns the agent process, performs ACP initialize
     * and session/new handshake, and reads available configuration (model, mode).
     *
     * Agents that fail to start or connect are reported with ERROR status.
     */
    fun startMonitoring(agentConfigs: List<AcpAgentConfig>) {
        if (monitoring) stopMonitoring()
        monitoring = true

        _agents.value = agentConfigs.map { config ->
            AcpAgentInfo(id = config.id, name = config.name, status = AcpAgentStatus.CONNECTING)
        }

        for (config in agentConfigs) {
            scope.launch { connectAgent(config) }
        }
    }

    /**
     * Stop monitoring all agents. Kills all spawned processes and cleans up.
     */
    fun stopMonitoring() {
        monitoring = false
        synchronized(connections) {
            for ((_, conn) in connections) conn.close()
            connections.clear()
        }
        _agents.value = _agents.value.map { it.copy(status = AcpAgentStatus.DISCONNECTED) }
    }

    /**
     * Change an agent's model via ACP session API.
     * Returns true if the model was successfully changed.
     */
    suspend fun setModel(agentId: String, modelValue: String): Boolean {
        val conn = synchronized(connections) { connections[agentId] } ?: return false
        return try {
            conn.session.setModel(ModelId(modelValue), null)
            updateAgentInfo(agentId) { it.copy(currentModel = modelValue) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ─── Internal: agent connection lifecycle ──────────────────────────

    private suspend fun connectAgent(config: AcpAgentConfig) {
        try {
            val connection = spawnAndInitialize(config)
            synchronized(connections) { connections[config.id] = connection }

            val info = readAgentInfo(config, connection)
            updateAgentInfo(config.id) { info.copy(status = AcpAgentStatus.CONNECTED) }
        } catch (e: Exception) {
            updateAgentInfo(config.id) {
                AcpAgentInfo(
                    id = config.id,
                    name = config.name,
                    status = AcpAgentStatus.ERROR,
                    errorMessage = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    private suspend fun spawnAndInitialize(config: AcpAgentConfig): AcpConnection {
        // Build process with environment
        val pb = ProcessBuilder(listOf(config.command) + config.args)
        pb.directory(File(config.cwd))
        for ((key, value) in config.env) {
            pb.environment()[key] = value
        }
        pb.redirectErrorStream(false)

        val process = pb.start()

        // Create ACP transport over the process's stdin/stdout
        val transport = StdioTransport(
            scope,
            Dispatchers.IO,
            process.inputStream.asSource().buffered(),
            process.outputStream.asSink().buffered(),
            "acp-${config.id}",
        )

        val protocol = Protocol(scope, transport, ProtocolOptions())

        // Create the client — no global elicitation handler needed
        val client = Client(protocol, null as GlobalElicitationHandler?)

        // Start the protocol
        protocol.start()

        // Initialize: negotiate capabilities with the agent
        val agentInfo = withTimeoutOrNull(INITIALIZE_TIMEOUT_MS) {
            client.initialize(ClientInfo())
        } ?: throw RuntimeException("ACP initialize timed out after ${INITIALIZE_TIMEOUT_MS}ms")

        // Create session operations factory that auto-approves permissions
        val operationsFactory = ClientOperationsFactory { sessionId, sessionResponse ->
            AutoApproveSessionOperations()
        }

        // Create a monitoring session
        val session = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            client.newSession(
                SessionCreationParameters(cwd = config.cwd, mcpServers = emptyList()),
                operationsFactory,
            )
        } ?: throw RuntimeException("ACP session/new timed out after ${SESSION_TIMEOUT_MS}ms")

        return AcpConnection(config, process, protocol, client, session, agentInfo)
    }

    private fun readAgentInfo(config: AcpAgentConfig, conn: AcpConnection): AcpAgentInfo {
        val capabilities = extractCapabilities(conn.agentInfo)
        val (currentModel, availableModels, currentMode, availableModes) = extractModelAndMode(conn.session)

        return AcpAgentInfo(
            id = config.id,
            name = config.name,
            currentModel = currentModel,
            availableModels = availableModels,
            currentMode = currentMode,
            availableModes = availableModes,
            capabilities = capabilities,
            status = AcpAgentStatus.CONNECTED,
        )
    }

    // ─── Internal: parsing helpers ──────────────────────────────────────

    private fun extractCapabilities(agentInfo: AgentInfo): List<String> {
        val caps = mutableListOf<String>()
        val capabilities: AgentCapabilities = agentInfo.capabilities

        if (capabilities.loadSession) caps.add("loadSession")
        caps.add("prompt")
        return caps
    }

    private fun extractModelAndMode(session: ClientSession): ModelModeInfo {
        var currentModel: String? = null
        var availableModels = emptyList<String>()
        var currentMode: String? = null
        var availableModes = emptyList<String>()

        try {
            if (session.modelsSupported) {
                val modelId: ModelId = session.currentModel.value
                currentModel = modelId.toString()
                availableModels = session.availableModels.map { it.name ?: it.toString() }
            }
        } catch (_: Exception) {
            // Model info not available
        }

        try {
            if (session.modesSupported) {
                val modeId: SessionModeId = session.currentMode.value
                currentMode = modeId.toString()
                availableModes = session.availableModes.map { it.name }
            }
        } catch (_: Exception) {
            // Mode info not available
        }

        return ModelModeInfo(currentModel, availableModels, currentMode, availableModes)
    }

    // ─── Internal: state updates ────────────────────────────────────────

    private fun updateAgentInfo(agentId: String, update: (AcpAgentInfo) -> AcpAgentInfo) {
        _agents.value = _agents.value.map { if (it.id == agentId) update(it) else it }
    }

    // ─── Inner types ────────────────────────────────────────────────────

    private class AcpConnection(
        val config: AcpAgentConfig,
        val process: Process,
        @Suppress("unused") val protocol: Protocol,
        @Suppress("unused") val client: Client,
        val session: ClientSession,
        val agentInfo: AgentInfo,
    ) {
        fun close() {
            runCatching {
                process.destroy()
                if (process.isAlive) {
                    Thread.sleep(100)
                    process.destroyForcibly()
                }
            }
        }
    }

    /**
     * Minimal ClientSessionOperations implementation that auto-approves
     * all permission requests. FileSystem/Terminal/Elicitation operations
     * use default implementations (which return errors if called).
     */
    private class AutoApproveSessionOperations : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            meta: JsonElement?,
        ): RequestPermissionResponse {
            // Auto-approve: prefer ALLOW_ALWAYS, then ALLOW_ONCE, then first option
            val selected = permissions.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ALWAYS }
                ?: permissions.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE }
                ?: permissions.firstOrNull()
            return if (selected != null) {
                RequestPermissionResponse(RequestPermissionOutcome.Selected(selected.optionId))
            } else {
                RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("")))
            }
        }

        override suspend fun notify(notification: SessionUpdate, meta: JsonElement?) {
            // Ignore session update notifications for MVP
        }
    }

    private data class ModelModeInfo(
        val currentModel: String?,
        val availableModels: List<String>,
        val currentMode: String?,
        val availableModes: List<String>,
    )

    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 30_000L
        private const val SESSION_TIMEOUT_MS = 60_000L
    }
}
