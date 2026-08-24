package com.angussoftware.fueldashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import kotlin.test.Test

/**
 * Real Compose UI tests for [ProviderContent] — the per-provider renderer.
 *
 * Replaces grep-style assertions with actual rendering: each provider type
 * (WINDOW_CREDIT / SPEND_BUDGET / RATE_LIMIT) plus the error, unavailable,
 * and loading states must render their distinguishing UI.
 */
@OptIn(ExperimentalTestApi::class)
class ProviderContentUiTest {

    private val config = ProviderConfig(id = "p1", kind = ProviderKind.ZAI, apiKey = "k", displayName = "My z.ai")

    private fun windowCreditReport() = ProviderReport(
        providerId = "p1",
        displayName = "My z.ai",
        type = ProviderType.WINDOW_CREDIT,
        remainingPct = 58,
        resetsAt = 1_760_000_000_000L,
        windowHours = 5.0,
        windows = listOf(
            ReportWindow("5h Token Window", 58, 1_760_000_000_000L, 5.0),
            ReportWindow("Session", 90, 1_760_000_000_000L, 5.0),
        ),
        rawDisplay = "tokens:42%",
    )

    private fun budgetReport() = ProviderReport(
        providerId = "p1",
        displayName = "OpenAI",
        type = ProviderType.SPEND_BUDGET,
        usedDollars = 25.0,
        limitDollars = 100.0,
        windows = listOf(ReportWindow("Monthly Budget", 75, 1_760_000_000_000L, 720.0)),
    )

    private fun rateLimitReport() = ProviderReport(
        providerId = "p1",
        displayName = "Groq",
        type = ProviderType.RATE_LIMIT,
        windows = listOf(
            ReportWindow("Requests/day", 70, 1_760_000_000_000L, 24.0),
            ReportWindow("Tokens/min", 50, 1_760_000_000_000L, 1.0 / 60.0),
        ),
    )

    private fun render(
        report: ProviderReport?,
        error: String? = null,
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    ProviderContent(
                        config = config,
                        report = report,
                        error = error,
                        showHelp = false,
                        titleStyle = MaterialTheme.typography.titleSmall,
                        contentSpacing = 8.dp,
                        isChecking = false,
                        onCheckJunieBalance = null,
                        boxedCreditBalance = false,
                    )
                }
            }
        }
        assertions()
    }

    @Test
    fun windowCredit_showsWindowNames() = render(windowCreditReport()) {
        onNodeWithText("My z.ai").assertExists("provider display name")
        onNodeWithText("5h Token Window").assertExists("WINDOW_CREDIT must show window name")
        onNodeWithText("Session").assertExists("second window")
    }

    @Test
    fun windowCredit_showsRemainingPercentage() = render(windowCreditReport()) {
        // 58% remaining must be rendered somewhere in the card body
        onAllNodesWithText("58%").assertCountEquals(1)
    }

    @Test
    fun spendBudget_showsMonthlySpendBar() = render(budgetReport()) {
        onNodeWithText("My z.ai").assertExists("provider display name comes from config")
        onNodeWithText("Monthly Budget").assertExists("SPEND_BUDGET must show budget window")
    }

    @Test
    fun rateLimit_showsBothWindows() = render(rateLimitReport()) {
        onNodeWithText("My z.ai").assertExists()
        onNodeWithText("Requests/day").assertExists()
        onNodeWithText("Tokens/min").assertExists()
    }

    @Test
    fun errorState_showsErrorBadge() = render(null, error = "HTTP 401") {
        onNodeWithText("\u26A0 Error").assertExists("error state must render the warning badge")
    }

    @Test
    fun unavailableReport_showsUnavailableBadge() = render(
        ProviderReport(
            providerId = "p1",
            displayName = "My z.ai",
            type = ProviderType.WINDOW_CREDIT,
            available = false,
        ),
    ) {
        onNodeWithText("UNAVAILABLE").assertExists("unavailable report must render the badge")
    }

    @Test
    fun healthyReport_noErrorOrUnavailableBadge() = render(windowCreditReport()) {
        onNodeWithText("UNAVAILABLE").assertDoesNotExist()
        onNodeWithText("\u26A0 Error").assertDoesNotExist()
    }
}
