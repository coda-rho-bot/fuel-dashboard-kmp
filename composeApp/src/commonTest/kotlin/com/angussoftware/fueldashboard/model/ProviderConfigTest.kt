package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderConfigTest {
    @Test
    fun connectedApiUsesRemoteDashboardDisplayName() {
        assertEquals("Remote Dashboard", ProviderKind.CONNECTED_API.displayName)
    }

    @Test
    fun junieNeedsNoApiKeyOrServerUrl() {
        val config = ProviderConfig(id = "junie", kind = ProviderKind.JUNIE)

        assertEquals("Junie", ProviderKind.JUNIE.displayName)
        assertEquals(ProviderCategory.LLM_PROVIDER, ProviderKind.JUNIE.category)
        assertEquals("", config.resolvedServerUrl())
        assertTrue(config.isConfigured)
    }

    @Test
    fun monthlyBudgetDefaultsToZeroAndIsSupportedBySpendBudgetProviders() {
        val config = ProviderConfig(id = "openai", kind = ProviderKind.OPENAI)

        assertEquals(0.0, config.monthlyBudgetUsd)
        assertTrue(ProviderKind.OPENAI.supportsMonthlyBudget)
        assertTrue(ProviderKind.ANTHROPIC.supportsMonthlyBudget)
        assertTrue(ProviderKind.MISTRAL.supportsMonthlyBudget)
    }
}