package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A help icon must not move when it is clicked.
 *
 * The popup was emitted as a sibling of the Icon inside the caller's Row. In a
 * start-aligned row its width grows into empty space and nothing visibly
 * moves, which is why this went unnoticed — but in an END-aligned row (the app
 * bar's "Updated …" line) that width pushes the icon left by most of the
 * tooltip's width, so the button jumps out from under the cursor mid-click.
 */
@OptIn(ExperimentalTestApi::class)
class HelpIconLayoutTest {

    private val help = "Long enough help text to give the tooltip a real width to push with."

    @Test
    fun doesNotMoveInAnEndAlignedRow() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("Updated 11:09:05")
                        HelpIcon(help)
                    }
                }
            }
        }

        val before = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()
        onNodeWithContentDescription(help).performClick()
        waitForIdle()
        val after = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()

        println("ICONPROBE end-aligned before.left=${before.left} after.left=${after.left}")
        assertEquals(before.left, after.left, "the help icon moved when clicked")
    }

    @Test
    fun doesNotMoveInAStartAlignedRow() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Text("5-hour")
                        HelpIcon(help)
                    }
                }
            }
        }

        val before = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()
        onNodeWithContentDescription(help).performClick()
        waitForIdle()
        val after = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()

        println("ICONPROBE start-aligned before.left=${before.left} after.left=${after.left}")
        assertEquals(before.left, after.left, "the help icon moved when clicked")
    }

    /**
     * The real shape of App.kt's "Updated …" row: SpaceBetween, with the icon
     * as the last child. SpaceBetween divides free space by CHILD COUNT, so a
     * popup emitted as a sibling turns 3 children into 4 and re-spreads the
     * whole row — the icon stops being last and jumps left.
     */
    @Test
    fun doesNotMoveInASpaceBetweenRow() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Updated 11:09:05")
                        Text("spacer-stand-in")
                        HelpIcon(help)
                    }
                }
            }
        }

        val before = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()
        onNodeWithContentDescription(help).performClick()
        waitForIdle()
        val after = onNodeWithContentDescription(help).getUnclippedBoundsInRoot()

        println("ICONPROBE space-between before.left=${before.left} after.left=${after.left}")
        assertEquals(before.left, after.left, "the help icon moved when clicked")
    }
}
