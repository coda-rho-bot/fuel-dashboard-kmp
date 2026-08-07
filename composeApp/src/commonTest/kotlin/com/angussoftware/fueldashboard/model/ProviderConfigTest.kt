package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderConfigTest {
    @Test
    fun connectedApiUsesRemoteDashboardDisplayName() {
        assertEquals("Remote Dashboard", ProviderKind.CONNECTED_API.displayName)
    }
}