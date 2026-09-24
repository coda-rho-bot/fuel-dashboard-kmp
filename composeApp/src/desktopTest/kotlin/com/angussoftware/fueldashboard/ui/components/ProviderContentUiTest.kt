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
import com.angussoftware.fueldashboard.presentation.SwitchRunStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        providerConfig: ProviderConfig = config,
        isSettling: Boolean = false,
        isSwapping: Boolean = false,
        isServingClaudeCode: Boolean = false,
        onSwapNow: (() -> Unit)? = null,
        switchStatus: SwitchRunStatus? = null,
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    ProviderContent(
                        config = providerConfig,
                        report = report,
                        error = error,
                        showHelp = false,
                        titleStyle = MaterialTheme.typography.titleSmall,
                        contentSpacing = 8.dp,
                        isChecking = false,
                        isServingClaudeCode = isServingClaudeCode,
                        onCheckJunieBalance = null,
                        boxedCreditBalance = false,
                        isSettling = isSettling,
                        isSwapping = isSwapping,
                        onSwapNow = onSwapNow,
                        switchStatus = switchStatus,
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
        // 58% remaining must be rendered somewhere in the card body, and it
        // must carry its unit: a bare "58%" reads as 58% *used*, which is the
        // complement of what this gauge means.
        onAllNodesWithText("58% left").assertCountEquals(1)
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

    // --- manual swap button -------------------------------------------------

    private val swapConfig = config.copy(activateCommand = "/bin/echo swapped", swapAwayBelowPct = 10)

    @Test
    fun swapButton_hiddenWhenNoCommandConfigured() = render(
        windowCreditReport(),
        onSwapNow = {},
    ) {
        // config has a blank activateCommand: the opt-in promise is that an
        // un-configured provider gains no control here.
        onAllNodesWithText("Swap").assertCountEquals(0)
    }

    @Test
    fun swapButton_hiddenWhenPlatformOffersNoAction() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        onSwapNow = null,
    ) {
        // A command is configured, but the caller passed no action (mobile, or
        // switchCommandsSupported == false). Nothing must render.
        onAllNodesWithText("Swap").assertCountEquals(0)
    }

    @Test
    fun swapButton_showsWhenConfigured() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        onSwapNow = {},
    ) {
        // The card no longer echoes the command: it is the provider's own
        // "switch to me", not a script whose text the reader must inspect.
        onNodeWithText("Swap").assertIsDisplayed()
        onAllNodesWithText("Runs: /bin/echo swapped").assertCountEquals(0)
    }

    @Test
    fun swapButton_disabledOnTheProviderAlreadyInUse() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        isServingClaudeCode = true,
        onSwapNow = {},
    ) {
        // Disabled rather than hidden: hiding it would make the row jump as
        // the active provider changes, and "you are already here" is worth
        // saying.
        // One label either way; the "● IN USE" badge is what explains the
        // disabled state, so the button does not repeat it.
        onNodeWithText("Swap").assertIsNotEnabled()
    }

    @Test
    fun swapButton_stillOfferedOnAnErroringCard() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        error = "429 Too Many Requests",
        onSwapNow = {},
    ) {
        // An erroring provider is when you most want to leave it. The old
        // full-width block sat below an early return for the error state and
        // so never rendered here; the header placement fixes that.
        onNodeWithText("Swap").assertIsDisplayed()
    }

    @Test
    fun swapButton_replacedBySpinnerWhileRunning() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        isSwapping = true,
        onSwapNow = {},
    ) {
        onAllNodesWithText("Swap").assertCountEquals(0)
        onNodeWithText("Swapping\u2026").assertIsDisplayed()
    }

    @Test
    fun swapButton_showsRefusalReason() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        onSwapNow = {},
        switchStatus = SwitchRunStatus(ok = false, message = "Not swapped — 3 of 20 sessions still working. Let them finish."),
    ) {
        // A refusal must be visible: otherwise "the fleet is busy" and "the
        // button is broken" look identical from the outside.
        onNodeWithText("\u26a0 Not swapped — 3 of 20 sessions still working. Let them finish.").assertIsDisplayed()
    }

    @Test
    fun swapButton_showsSuccessLine() = render(
        windowCreditReport(),
        providerConfig = swapConfig,
        onSwapNow = {},
        switchStatus = SwitchRunStatus(ok = true, message = "Switched — ok"),
    ) {
        onNodeWithText("\u2713 Switched — ok").assertIsDisplayed()
    }

    // --- settling vs genuinely unavailable ----------------------------------

    /** Polled successfully, but the payload carried no usable reading. */
    private fun emptyReport() = ProviderReport(
        providerId = "p1",
        displayName = "My z.ai",
        type = ProviderType.WINDOW_CREDIT,
        available = false,
    )

    @Test
    fun emptyReading_whileSettling_spinsInsteadOfAccusing() = render(
        emptyReport(),
        isSettling = true,
    ) {
        onNodeWithText("Connecting\u2026").assertIsDisplayed()
        // The badge is the whole point: one empty reading must not be
        // announced as a broken provider.
        onAllNodesWithText("UNAVAILABLE").assertCountEquals(0)
        onAllNodesWithText("No usage data (unlimited or static)").assertCountEquals(0)
    }

    @Test
    fun emptyReading_onceSettled_saysUnavailable() = render(
        emptyReport(),
        isSettling = false,
    ) {
        // Still honest once it has repeated: a spinner forever would hide a
        // provider that genuinely has nothing to say.
        onNodeWithText("UNAVAILABLE").assertIsDisplayed()
        onAllNodesWithText("Connecting\u2026").assertCountEquals(0)
    }

    @Test
    fun noReportAtAll_spins() = render(null) {
        onNodeWithText("Connecting\u2026").assertIsDisplayed()
        onAllNodesWithText("UNAVAILABLE").assertCountEquals(0)
    }

    @Test
    fun anErrorStillWinsOverSettling() = render(
        emptyReport(),
        error = "boom",
        isSettling = true,
    ) {
        // A real failure must never be softened into "connecting".
        onNodeWithText("\u26A0 Error").assertIsDisplayed()
        onNodeWithText("boom").assertIsDisplayed()
    }
}
