package com.angussoftware.fueldashboard.model

import kotlinx.serialization.Serializable

/**
 * Configuration for a single ACP agent to monitor.
 *
 * Stored in settings as part of [AgentSettings]. The desktop main.kt maps
 * this to the desktop-only [AcpAgentConfig] (in acp/ package) before passing
 * to AcpAgentManager.
 *
 * @param id      Unique identifier (e.g., "agent-abc123")
 * @param name    Display name (e.g., "Coda")
 * @param command Executable path to spawn (e.g., "/usr/bin/letta-acp")
 * @param args    Command-line arguments as a space-separated string (e.g., "--yolo")
 * @param env     Optional environment variables for the process
 */
@Serializable
data class AgentConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: String = "",
    val env: Map<String, String> = emptyMap(),
)

/**
 * Serializable settings wrapper for agent configurations.
 *
 * The agents list is serialized as JSON and stored in a single settings key
 * via the platform-agnostic SettingsStore.
 */
@Serializable
data class AgentSettings(
    val agents: List<AgentConfig> = emptyList(),
)
