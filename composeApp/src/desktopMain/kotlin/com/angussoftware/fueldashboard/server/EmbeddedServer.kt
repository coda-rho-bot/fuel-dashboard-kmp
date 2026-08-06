package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.database.DecisionRepository
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
import java.net.NetworkInterface

/**
 * Embedded HTTP server — desktop app IS the orchestrator.
 * Serves fuel data to mobile devices on the same LAN.
 */
class EmbeddedServer(
    private val repository: DecisionRepository? = null,
    private val port: Int = DEFAULT_PORT,
    private val host: String = DEFAULT_HOST,
) {
    companion object {
        const val DEFAULT_PORT = 8321
        const val DEFAULT_HOST = "0.0.0.0"
        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 1_000L
    }

    private var server: KtorServer<*, *>? = null
    private val startTimeMs = System.currentTimeMillis()

    @Volatile var fuelState: FuelResponse? = null
    @Volatile var agents: List<FleetAgent> = emptyList()
    @Volatile var alerts: List<String> = emptyList()

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

    private fun Application.configureRouting() {
        install(CORS) { anyHost() }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }

        routing {
            get("/") {
                call.respond(ServiceInfo("fuel-dashboard", "2.0", listOf("GET /fuel", "GET /decisions", "GET /agents", "GET /alerts", "GET /health")))
            }

            get("/fuel") {
                val state = fuelState
                if (state != null) call.respond(state)
                else call.respondText("{\"providers\":{}}", contentType = ContentType.Application.Json)
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

            get("/agents") { call.respond(AgentsResponse(agents)) }
            get("/alerts") { call.respond(AlertsResponse(alerts)) }

            get("/health") {
                val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000
                call.respond(HealthResponse("ok", uptimeSec, currentPid()))
            }
        }
    }

    private fun currentPid(): Long = try {
        ProcessHandle.current().pid()
    } catch (e: Exception) {
        ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: -1
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
                    return "http://${addr.hostAddress}:8321"
                }
            }
        }
        "http://localhost:8321"
    } catch (e: Exception) {
        "http://localhost:8321"
    }
}

@Serializable private data class ServiceInfo(val service: String, val version: String, val endpoints: List<String>)
@Serializable private data class HealthResponse(val status: String, val uptime: Long, val pid: Long)
