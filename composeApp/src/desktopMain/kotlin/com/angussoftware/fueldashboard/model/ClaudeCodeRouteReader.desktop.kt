package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.network.SharedHttpClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Parses `~/.claude/settings.json` — the file Claude Code reads at launch and
 * the one a provider switch rewrites.
 *
 * Only routing-relevant fields are read. Nothing here is written, and nothing
 * is cached: the file changes underneath us whenever the provider is switched,
 * so each poll re-reads it.
 */
internal actual fun readClaudeCodeRoute(): ClaudeCodeRoute? {
    val file = File(System.getProperty("user.home"), ".claude/settings.json")
    if (!file.isFile || !file.canRead()) return null

    val root = runCatching {
        SharedHttpClient.json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull() ?: return null

    val baseUrl = root["env"]?.jsonObject
        ?.get("ANTHROPIC_BASE_URL")?.jsonPrimitive?.contentOrNull
        ?.trim()?.ifBlank { null }

    val permissionMode = root["permissions"]?.jsonObject
        ?.get("defaultMode")?.jsonPrimitive?.contentOrNull
        ?.trim()?.ifBlank { null }

    return ClaudeCodeRoute(baseUrl = baseUrl, permissionMode = permissionMode)
}
