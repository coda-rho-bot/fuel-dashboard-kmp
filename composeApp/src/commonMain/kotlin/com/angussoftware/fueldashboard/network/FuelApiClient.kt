package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class FuelApiClient(
    baseUrl: String = "http://127.0.0.1:8321",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val base = baseUrl.trimEnd('/')

    suspend fun getFuel(): FuelResponse =
        client.get("$base/fuel").body()

    suspend fun getDecisions(limit: Int = 20): DecisionsResponse =
        client.get("$base/decisions?limit=$limit").body()

    suspend fun getAgents(): AgentsResponse =
        client.get("$base/agents").body()

    suspend fun getAlerts(): AlertsResponse =
        client.get("$base/alerts").body()

    suspend fun getHealth(): String =
        client.get("$base/health").bodyAsText()

    fun close() {
        client.close()
    }
}
