package com.angussoftware.fueldashboard.network

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Submits in-app feedback as a Forgejo issue on the project repo —
 * a traditional issue tracker where reports are tracked and discussed.
 *
 * Zero-config from the user's perspective: URL/repo/token default to the project
 * Forgejo with a baked-in write:issue token. Works OOB without any sync.
 */
object FeedbackSubmitter {

    sealed class Result {
        /** Issue created; [url] links to it. */
        data class Success(val url: String, val number: Int) : Result()
        /** Submission failed; [message] explains why (auth, network, scope). */
        data class Failure(val message: String) : Result()
    }

    suspend fun submit(
        forgejoUrl: String,
        repo: String,
        token: String,
        title: String,
        body: String,
    ): Result {
        if (token.isBlank()) return Result.Failure("Feedback token is missing.")
        val url = forgejoUrl.trimEnd('/') + "/api/v1/repos/$repo/issues"
        return try {
            val response = SharedHttpClient.client.post(url) {
                header(HttpHeaders.Authorization, "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                    }.toString(),
                )
            }
            if (response.status.isSuccess()) {
                parseSuccess(response.bodyAsText(), forgejoUrl, repo)
            } else {
                mapFailure(response.status.value, response.bodyAsText().take(300))
            }
        } catch (e: Exception) {
            Result.Failure("Could not reach the issue tracker — ${e.message ?: e::class.simpleName}")
        }
    }

    /** Parses a created-issue response into [Result.Success]. */
    internal fun parseSuccess(responseBody: String, forgejoUrl: String, repo: String): Result.Success {
        val issue = Json.parseToJsonElement(responseBody).jsonObject
        val htmlUrl = issue["html_url"]?.jsonPrimitive?.content
            ?: forgejoUrl.trimEnd('/') + "/$repo/issues"
        val number = issue["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        return Result.Success(url = htmlUrl, number = number)
    }

    /** Maps an HTTP failure to a user-actionable message. */
    internal fun mapFailure(status: Int, text: String): Result.Failure = Result.Failure(
        when (status) {
            403 -> "Feedback token was rejected (403) — re-sync settings from the main dashboard."
            401 -> "Feedback token is invalid (401) — re-sync settings from the main dashboard."
            else -> "HTTP $status: $text"
        },
    )
}
