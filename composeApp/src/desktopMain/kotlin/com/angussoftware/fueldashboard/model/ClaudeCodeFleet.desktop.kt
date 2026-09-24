package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.network.SharedHttpClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Counts live Claude Code sessions by status.
 *
 * Liveness is decided by whether the recorded pid still exists, NOT by how old
 * `statusUpdatedAt` is. That timestamp records when the session last *changed*
 * status, so a session idle since this morning carries a timestamp hours old
 * while being perfectly alive — treating that as stale reported 20 of 26 live
 * sessions as unknown and would have blocked every switch forever.
 *
 * A registry file whose process is gone is the genuinely stale case: Claude
 * Code leaves the file behind when a session dies without cleaning up.
 */
internal actual fun readClaudeCodeFleet(): ClaudeCodeFleet? {
    val dir = File(System.getProperty("user.home"), ".claude/sessions")
    if (!dir.isDirectory || !dir.canRead()) return null

    // The directory also holds .key files, which are not session records.
    val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
        ?: return null

    var busy = 0
    var idle = 0
    var unknown = 0

    for (file in files) {
        val obj = runCatching {
            SharedHttpClient.json.parseToJsonElement(file.readText()).jsonObject
        }.getOrNull()
        if (obj == null) {
            unknown++
            continue
        }

        // A record for a process that no longer exists is leftover, not a
        // session — it is skipped entirely rather than counted as unknown,
        // which would permanently prevent the fleet from ever reading quiet.
        val pid = obj["pid"]?.jsonPrimitive?.longOrNull
        if (pid == null || !ProcessHandle.of(pid).isPresent) continue

        when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "busy" -> busy++
            "idle" -> idle++
            else -> unknown++
        }
    }

    return ClaudeCodeFleet(busy = busy, idle = idle, unknown = unknown)
}
