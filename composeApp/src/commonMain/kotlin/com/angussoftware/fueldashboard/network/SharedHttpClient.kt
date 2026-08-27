package com.angussoftware.fueldashboard.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object SharedHttpClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000  // 20s — must exceed connect timeout (15s) per review 1845
            connectTimeoutMillis = 15_000  // 15s — Cloudflare tunnel on corporate networks can exceed 5s
            socketTimeoutMillis = 15_000   // 15s
        }
    }
}
