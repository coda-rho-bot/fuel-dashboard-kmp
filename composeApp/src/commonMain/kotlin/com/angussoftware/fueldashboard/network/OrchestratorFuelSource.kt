package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.FuelSource

/**
 * Wraps the existing [FuelApiClient] as a [FuelSource].
 *
 * Used in CONNECTED mode — polls the orchestrator's /fuel endpoint.
 */
class OrchestratorFuelSource(
    private val apiClient: FuelApiClient,
) : FuelSource {

    override val displayName: String = "Remote Dashboard"

    override suspend fun getFuel(): FuelResponse = apiClient.getFuel()
}
