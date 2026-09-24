package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constructing the ViewModel with providers already stored.
 *
 * This is the gap that let a startup crash through. The constructor calls
 * activateAdapters → createAdapter, so any property those touch must be
 * declared above them in the class body — a property declared lower down is
 * still null at that point. Nothing caught it: the compiler cannot see it, and
 * every other test either builds no ViewModel or builds one with no providers
 * configured, so the adapter path never ran.
 *
 * The test JVM redirects user.home into the build tree, so writing settings
 * here touches a sandbox and never the developer's real config.
 */
class ViewModelConstructionTest {

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    @Test
    fun constructsWithAConfiguredProvider() {
        FuelSettingsStore.saveMultiProvider(
            MultiProviderSettings(
                providers = listOf(
                    ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "literal-key"),
                ),
            ),
        )

        // Construction alone is the assertion: it previously threw a
        // NullPointerException before any window could be drawn.
        val viewModel = FuelViewModel()
        try {
            assertEquals(1, viewModel.state.value.settings.providers.size)
            assertTrue(viewModel.state.value.activeProviders.isNotEmpty())
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun constructsWhenACredentialReferenceCannotBeResolved() {
        // An unresolvable reference must degrade to a provider error, not take
        // the whole app down during construction.
        FuelSettingsStore.saveMultiProvider(
            MultiProviderSettings(
                providers = listOf(
                    ProviderConfig(
                        id = "zai-1",
                        kind = ProviderKind.ZAI,
                        apiKey = "cmd:/nonexistent-secret-helper",
                    ),
                ),
            ),
        )

        val viewModel = FuelViewModel()
        try {
            assertEquals(1, viewModel.state.value.settings.providers.size)
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun constructsWithNoProvidersAtAll() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
        val viewModel = FuelViewModel()
        try {
            assertTrue(viewModel.state.value.activeProviders.isEmpty())
        } finally {
            viewModel.close()
        }
    }
}
