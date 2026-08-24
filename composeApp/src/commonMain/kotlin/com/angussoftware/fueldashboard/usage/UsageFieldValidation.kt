package com.angussoftware.fueldashboard.usage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Thrown when a usage-ingestion field is present but malformed.
 * Callers (HTTP POST /v1/usage, MCP report_usage) map this to a 400 /
 * tool-error naming the offending field — never a silent 0 coercion.
 */
class UsageFieldError(val field: String, val reason: String) : Exception("$field: $reason")

/**
 * Parses a usage-ingestion numeric field.
 *
 * Contract:
 * - absent → [default] (legitimate defaulting; the field is optional)
 * - present, valid integer → its value
 * - present but malformed (non-numeric, or a JSON object/array/null) → [UsageFieldError]
 *
 * This replaces the `?: 0L` silent-coercion pattern that recorded
 * `"input_tokens": "abc"` as a zero-token usage row.
 */
fun JsonObject.longFieldOr(name: String, default: Long): Long {
    val element = this[name] ?: return default
    val primitive = element as? JsonPrimitive
        ?: throw UsageFieldError(name, "must be a number, got ${element::class.simpleName}")
    return primitive.longOrNull
        ?: primitive.content.toLongOrNull()
        ?: throw UsageFieldError(name, "must be a valid integer, got \"${primitive.content}\"")
}

/** [longFieldOr] with absent → null (for fields whose absence means "now"/none). */
fun JsonObject.longFieldOrNull(name: String): Long? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive
        ?: throw UsageFieldError(name, "must be a number, got ${element::class.simpleName}")
    return primitive.longOrNull
        ?: primitive.content.toLongOrNull()
        ?: throw UsageFieldError(name, "must be a valid integer, got \"${primitive.content}\"")
}
