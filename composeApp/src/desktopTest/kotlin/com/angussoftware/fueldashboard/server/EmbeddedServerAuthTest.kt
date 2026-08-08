package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.settings.loadOrCreateApiKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbeddedServerAuthTest {
    @Test
    fun apiKeyIsGeneratedOnceAndThenReused() {
        var persistedKey = ""
        var generationCount = 0

        val firstKey = loadOrCreateApiKey(
            load = { persistedKey },
            save = { persistedKey = it },
            generate = { "generated-${++generationCount}" },
        )
        val restartedKey = loadOrCreateApiKey(
            load = { persistedKey },
            save = { persistedKey = it },
            generate = { "generated-${++generationCount}" },
        )

        assertEquals("generated-1", firstKey)
        assertEquals(firstKey, restartedKey)
        assertEquals(1, generationCount)
    }

    @Test
    fun bearerAuthorizationRejectsMissingAndIncorrectKeys() {
        assertEquals(
            "Unauthorized: provide Authorization: Bearer <API key>.",
            bearerAuthorizationError(expectedKey = "expected-key", authorizationHeader = null),
        )
        assertEquals(
            "Unauthorized: provide Authorization: Bearer <API key>.",
            bearerAuthorizationError(expectedKey = "expected-key", authorizationHeader = "Bearer wrong-key"),
        )
    }

    @Test
    fun bearerAuthorizationAcceptsTheConfiguredKeyOnly() {
        assertNull(
            bearerAuthorizationError(
                expectedKey = "expected-key",
                authorizationHeader = "Bearer expected-key",
            ),
        )
        assertEquals(
            "Unauthorized: provide Authorization: Bearer <API key>.",
            bearerAuthorizationError(expectedKey = "expected-key", authorizationHeader = "Basic expected-key"),
        )
    }
}