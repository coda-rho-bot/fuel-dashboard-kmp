package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test

/**
 * Real rendering tests for [BudgetBar] and [EmptyTabState].
 *
 * BudgetBar contract (docs + i18n fix task_37): dollar amounts always
 * dot-decimal regardless of locale (formatRoot), `$X.XX / $Y.YY` when a
 * budget limit is set, `$X.XX used` when not, label "Monthly Spend".
 */
@OptIn(ExperimentalTestApi::class)
class BudgetBarUiTest {

    private fun renderBudgetBar(
        used: Double,
        limit: Double?,
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    Column {
                        BudgetBar(usedDollars = used, limitDollars = limit)
                    }
                }
            }
        }
        assertions()
    }

    @Test
    fun withLimit_showsAmountsAndLabel() = renderBudgetBar(used = 25.0, limit = 100.0) {
        onNodeWithText("Monthly Spend").assertExists("budget label")
        onNodeWithText("$25.00 / $100.00").assertExists("used/limit format with dot decimal")
    }

    @Test
    fun withoutLimit_showsUsedOnly() = renderBudgetBar(used = 13.5, limit = null) {
        onNodeWithText("$13.50 used").assertExists("no-limit format")
    }

    @Test
    fun emptyTabState_showsTitleMessageAndHint() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    EmptyTabState(
                        title = "Collecting data…",
                        message = "Usage metrics appear here once the dashboard has polled your providers a few times.",
                        hint = "Add providers in Settings and wait a few minutes for the first poll cycle.",
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Collecting data…").assertExists("empty-state title")
        onNodeWithText("Usage metrics appear here once the dashboard has polled your providers a few times.")
            .assertExists("empty-state message")
        onNodeWithText("Add providers in Settings and wait a few minutes for the first poll cycle.")
            .assertExists("empty-state hint")
    }
}
