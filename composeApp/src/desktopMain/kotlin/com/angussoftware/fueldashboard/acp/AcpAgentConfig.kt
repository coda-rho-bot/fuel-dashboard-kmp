package com.angussoftware.fueldashboard.acp

/**
 * Configuration for a single ACP agent to monitor.
 *
 * ACP (Agent Client Protocol) communicates over stdio (JSON-RPC 2.0).
 * The manager spawns the agent process and communicates via its stdin/stdout.
 *
 * For Letta Code agents, the command is typically `letta-acp` with environment
 * variables pointing to the Letta server backend.
 *
 * @param id Unique identifier (e.g., "coda")
 * @param name Display name (e.g., "Coda")
 * @param command The executable to spawn (e.g., "letta-acp")
 * @param args Command-line arguments (e.g., ["--yolo"])
 * @param env Environment variables for the process
 * @param cwd Working directory for the process
 */
data class AcpAgentConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = listOf("--yolo"),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = System.getProperty("user.home"),
) {
    companion object {
        /**
         * Harry's fleet of Letta Code agents.
         *
         * These spawn `letta-acp` processes that connect to the Letta server
         * backends on ports 14601-14605. The ports are only active when the
         * Letta Code desktop app is running for each agent.
         *
         * Agent IDs come from the consortium agents.yaml configuration.
         */
        fun defaultFleet(lettaAcpPath: String? = null): List<AcpAgentConfig> {
            val acpPath = lettaAcpPath ?: findLettaAcp() ?: return emptyList()
            val home = System.getProperty("user.home")

            return listOf(
                AcpAgentConfig(
                    id = "coda",
                    name = "Coda",
                    command = acpPath,
                    env = mapOf(
                        "LETTA_ACP_BACKEND" to "remote",
                        "LETTA_AGENT_ID" to "agent-b499137a-e1dd-4427-b9df-73e87adfce9e",
                        "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14601",
                        "NODE_OPTIONS" to "--experimental-websocket",
                    ),
                    cwd = home,
                ),
                AcpAgentConfig(
                    id = "angus",
                    name = "Angus",
                    command = acpPath,
                    env = mapOf(
                        "LETTA_ACP_BACKEND" to "remote",
                        "LETTA_AGENT_ID" to "agent-c51de213-2275-4d1d-9ed4-8ccfb7047e52",
                        "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14602",
                        "NODE_OPTIONS" to "--experimental-websocket",
                    ),
                    cwd = home,
                ),
                AcpAgentConfig(
                    id = "beacon",
                    name = "Beacon",
                    command = acpPath,
                    env = mapOf(
                        "LETTA_ACP_BACKEND" to "remote",
                        "LETTA_AGENT_ID" to "agent-e6f1a549-e06c-4510-b8ea-506f0ebbd211",
                        "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14603",
                        "NODE_OPTIONS" to "--experimental-websocket",
                    ),
                    cwd = home,
                ),
                AcpAgentConfig(
                    id = "forge",
                    name = "FORGE",
                    command = acpPath,
                    env = mapOf(
                        "LETTA_ACP_BACKEND" to "remote",
                        "LETTA_AGENT_ID" to "agent-2ee946fb-e74c-4628-9d9a-705fa567afb3",
                        "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14604",
                        "NODE_OPTIONS" to "--experimental-websocket",
                    ),
                    cwd = home,
                ),
                AcpAgentConfig(
                    id = "sinter",
                    name = "Sinter",
                    command = acpPath,
                    env = mapOf(
                        "LETTA_ACP_BACKEND" to "remote",
                        "LETTA_AGENT_ID" to "agent-5b2254e8-9582-4b39-87be-c9776c958c95",
                        "LETTA_APP_SERVER_URL" to "ws://127.0.0.1:14605",
                        "NODE_OPTIONS" to "--experimental-websocket",
                    ),
                    cwd = home,
                ),
                // NOTE: "linus" (port 14606) is intentionally omitted — no Letta Code
                // server listens on that port. Linus is not an active fleet agent.
                // Re-add it when a Letta Code instance is configured for port 14606.
            )
        }

        /**
         * Attempt to locate the `letta-acp` executable on the system.
         * Checks nvm paths and PATH.
         */
        private fun findLettaAcp(): String? {
            // Check common nvm paths
            val home = System.getProperty("user.home")
            val nvmPaths = listOf(
                "$home/.nvm/versions/node/v22.22.3/bin/letta-acp",
                "$home/.nvm/versions/node/v22.22.2/bin/letta-acp",
            )
            for (path in nvmPaths) {
                if (java.io.File(path).exists()) return path
            }

            // Try PATH
            return try {
                val which = ProcessBuilder("which", "letta-acp")
                    .redirectErrorStream(true)
                    .start()
                val output = which.inputStream.bufferedReader().readText().trim()
                if (which.waitFor() == 0 && output.isNotEmpty() && java.io.File(output).exists()) {
                    output
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
