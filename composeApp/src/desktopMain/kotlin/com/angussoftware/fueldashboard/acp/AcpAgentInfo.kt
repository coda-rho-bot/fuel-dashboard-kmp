package com.angussoftware.fueldashboard.acp

/**
 * Information about a monitored ACP agent, read via the ACP protocol.
 *
 * Populated after calling `initialize` and `session/new` on the agent.
 *
 * @param id The agent's unique ID (from config)
 * @param name The agent's display name
 * @param currentModel The model the agent is currently using (if reported)
 * @param availableModels Models the agent can use (if reported)
 * @param currentMode The agent's current mode (if reported)
 * @param availableModes Modes the agent supports (if reported)
 * @param capabilities Agent capabilities from the initialize response
 * @param status Connection status of this agent
 * @param errorMessage Error message if status is ERROR
 * @param retryCount Number of consecutive connection retries attempted (0 = fresh/connected)
 * @param nextRetryMs Epoch millis when the next retry will be attempted (0 = no retry scheduled)
 */
data class AcpAgentInfo(
    val id: String,
    val name: String,
    val currentModel: String? = null,
    val availableModels: List<String> = emptyList(),
    val currentMode: String? = null,
    val availableModes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val status: AcpAgentStatus = AcpAgentStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val nextRetryMs: Long = 0L,
)

/**
 * Connection status for a monitored agent.
 */
enum class AcpAgentStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
