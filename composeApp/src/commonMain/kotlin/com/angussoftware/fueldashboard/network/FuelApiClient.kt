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
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class FuelApiClient(
    baseUrl: String = "http://127.0.0.1:8322",
    private val apiKey: String = "",
) {
    val client: HttpClient
        get() = SharedHttpClient.client

    private val base = baseUrl.trimEnd('/')

    private fun HttpRequestBuilder.withAuth(): HttpRequestBuilder = apply {
        if (apiKey.isNotBlank()) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    suspend fun getFuel(): FuelResponse =
        client.get("$base/fuel") { withAuth() }.body()

    suspend fun getDecisions(limit: Int = 20): DecisionsResponse =
        client.get("$base/decisions?limit=$limit") { withAuth() }.body()

    suspend fun getAgents(): AgentsResponse =
        client.get("$base/agents") { withAuth() }.body()

    suspend fun getAlerts(): AlertsResponse =
        client.get("$base/alerts") { withAuth() }.body()

    /** Health check never requires auth. */
    suspend fun getHealth(): String =
        client.get("$base/health").bodyAsText()

    fun close() = Unit
}
