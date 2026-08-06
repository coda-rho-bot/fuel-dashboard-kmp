package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FleetAgent
import com.angussoftware.fueldashboard.model.FuelResponse
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer as KtorServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory

/**
 * Lightweight embedded HTTP server that exposes the same REST API as the
 * Node.js fuel-orchestrator.  Runs on the desktop (JVM) target only and serves
 * fuel data to mobile devices on the same local network.
 *
 * State is pushed in from the ViewModel via the public `@Volatile` properties —
 * the server never polls or couples directly to the data layer.
 */
class EmbeddedServer(
    private val port: Int = DEFAULT_PORT,
    private val host: String = DEFAULT_HOST,
) {
    companion object {
        const val DEFAULT_PORT = 8321
        const val DEFAULT_HOST = "0.0.0.0" // 0.0.0.0 = bind all interfaces for LAN access

        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 1_000L
    }

    private var server: KtorServer<*, *>? = null
    private val startTimeMs = System.currentTimeMillis()

    // ── Volatile state — updated by the ViewModel, read by request handlers ──

    @Volatile
    var fuelState: FuelResponse? = null

    @Volatile
    var decisions: List<Decision> = emptyList()

    @Volatile
    var agents: List<FleetAgent> = emptyList()

    @Volatile
    var alerts: List<String> = emptyList()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Starts the server on background threads.  Returns immediately.
     * Calling [start] when already running is a no-op.
     */
    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, host = host, port = port) {
            configureRouting()
        }
        server?.start(wait = false)
        println("[EmbeddedServer] Listening on http://$host:$port")
    }

    /**
     * Gracefully stops the server, allowing in-flight requests to complete.
     */
    fun stop() {
        server?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        server = null
    }

    // ── Routing ───────────────────────────────────────────────────────────

    private fun Application.configureRouting() {
        install(CORS) { anyHost() }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }

        routing {
            // GET / — service info + endpoint list
            get("/") {
                call.respond(
                    ServiceInfo(
                        service = "fuel-dashboard",
                        version = "2.0",
                        endpoints =
                            listOf(
                                "GET /fuel",
                                "GET /decisions",
                                "GET /agents",
                                "GET /alerts",
                                "GET /health",
                            ),
                    ),
                )
            }

            // GET /fuel — current fuel state (from ViewModel polling)
            get("/fuel") {
                val state = fuelState
                if (state != null) {
                    call.respond(state)
                } else {
                    call.respondText(
                        "{\"providers\":{}}",
                        contentType = ContentType.Application.Json,
                    )
                }
            }

            // GET /decisions?limit=N — recent decisions (empty list until SQLite is wired)
            get("/decisions") {
                val limit =
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?.coerceIn(1, 100) ?: 20
                call.respond(DecisionsResponse(decisions.take(limit)))
            }

            // GET /agents — fleet agents
            get("/agents") {
                call.respond(AgentsResponse(agents))
            }

            // GET /alerts — active alerts
            get("/alerts") {
                call.respond(AlertsResponse(alerts))
            }

            // GET /health — health check
            get("/health") {
                val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000
                call.respond(
                    HealthResponse(
                        status = "ok",
                        uptime = uptimeSec,
                        pid = currentPid(),
                    ),
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun currentPid(): Long =
        try {
            ProcessHandle.current().pid()
        } catch (e: Exception) {
            // Fallback for JVMs without ProcessHandle
            ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: -1
        }
}

// ── Inline response models ─────────────────────────────────────────────────

@Serializable
private data class ServiceInfo(
    val service: String,
    val version: String,
    val endpoints: List<String>,
)

@Serializable
private data class HealthResponse(
    val status: String,
    val uptime: Long,
    val pid: Long,
)
