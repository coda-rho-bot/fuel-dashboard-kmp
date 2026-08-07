package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.util.epochMillis

data class JunieBalanceInfo(
    val balance: Double,
    val license: String?,
)

fun parseJunieBalance(output: String): JunieBalanceInfo? {
    val balance = Regex("Balance left: \\$(\\d+(?:\\.\\d+)?)").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    val license = Regex("License: (.+)").find(output)?.groupValues?.get(1)?.trim()
    return balance?.let { JunieBalanceInfo(it, license) }
}

/**
 * Provides Junie's last-known monthly balance.
 *
 * Balance checking requires the `junie-credits` helper script to be installed in PATH.
 * The standard Junie CLI (`junie`) does not expose balance information non-interactively,
 * so this helper script is a separate dependency. Polling is intentionally cache-only
 * because invoking junie-credits charges for a check; [checkBalance] is called only by
 * an explicit user action.
 */
class JunieProviderAdapter(
    override val providerId: String,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Junie"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    override suspend fun poll(): ProviderReport = cachedReport()

    suspend fun checkBalance(): ProviderReport {
        val info = parseJunieBalance(runJunieCredits())
            ?: throw IllegalStateException("Could not parse Junie balance")
        val checkedAt = epochMillis()
        saveStringSetting(FuelSettingsKeys.JUNIE_BALANCE, info.balance.toString())
        saveStringSetting(FuelSettingsKeys.JUNIE_LICENSE, info.license.orEmpty())
        saveStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, checkedAt.toString())
        return cachedReport()
    }

    override fun close() = Unit

    private fun cachedReport(): ProviderReport {
        val balance = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull()
        val license = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null }
        val lastChecked = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull()
        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            usedDollars = 0.0,
            limitDollars = balance,
            resetsAt = lastChecked,
            detail = license,
        )
    }
}

/** Actual implementation invokes the local junie-credits helper script on desktop only. */
internal expect suspend fun runJunieCredits(): String

/** Whether this platform can run the junie-credits balance command (checks PATH for the helper script). */
internal expect val canCheckJunieBalance: Boolean