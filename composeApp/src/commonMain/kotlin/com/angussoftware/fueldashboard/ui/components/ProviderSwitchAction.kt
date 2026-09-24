package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Swap" — makes this provider the active one by running its activate command.
 *
 * Sits inline beside the provider's name rather than as a block at the bottom
 * of the card, because it belongs to that provider: the whole point of the
 * inverted semantics is that the button on a card means "switch to this one",
 * and a full-width button at the foot of a card reads as an action on the card
 * as a whole.
 *
 * The caller decides whether this is offered at all: [onSwapNow] is null on
 * every platform and provider where it is not, and this then renders nothing.
 * That keeps the opt-in promise in `ProviderConfig` — a provider with no
 * command configured gains no control here.
 *
 * Disabled, not hidden, on the provider already in use. Hiding it would make
 * the row jump as the active provider changes, and "you are already here" is
 * worth saying.
 *
 * There is deliberately no confirmation dialog. The seatbelt for this action
 * is the fleet gate in the view model, which refuses while any agent session
 * is mid-turn; a dialog would only add a click to an action that is already
 * blocked when it would be dangerous.
 */
@Composable
fun ProviderSwapButton(
    /**
     * Whether this provider has an activate command at all.
     *
     * Checked here as well as by the caller, deliberately. The opt-in promise
     * is that a provider with nothing configured gains no control, and a
     * guarantee about a destructive action is worth enforcing where the
     * control is built rather than only where it is passed in — a boolean
     * rather than the command text, since the card no longer displays it.
     */
    hasActivateCommand: Boolean,
    isSwapping: Boolean,
    isActive: Boolean,
    onSwapNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onSwapNow == null || !hasActivateCommand) return

    if (isSwapping) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Swapping…", style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    // Tonal rather than a plain text button: it needs to read as a control,
    // since it does something irreversible. Tonal rather than filled because
    // it sits beside the provider's name and a primary-filled button there
    // would outweigh the title it belongs to.
    //
    // Height and padding are overridden because Material's default button
    // metrics (40dp min) are sized for a standalone action and would stretch
    // the header row this sits inside.
    // Material reserves a 48dp interactive minimum around a button as
    // INVISIBLE padding, which lands on top of whatever gap the parent row
    // asks for — so an 8dp arrangement rendered as 8dp beside the badge and
    // noticeably more beside the button. Opting out makes the spacing the row
    // specifies the spacing you see. Safe here: this is a desktop-only control
    // driven by a mouse, and 28dp tall is a comfortable target for one.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    FilledTonalButton(
        onClick = onSwapNow,
        enabled = !isActive,
        modifier = modifier.heightIn(min = 28.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        // Just "Swap". The card's own "● IN USE" badge sits inches away and
        // already says why this is disabled, so a second label repeating it
        // only crowds the row.
        Text("Swap", style = MaterialTheme.typography.labelSmall)
    }
    }
}

/**
 * Outcome of the last swap attempt on this provider, and the way past a
 * refusal.
 *
 * A refusal is the case that most needs saying: without it, "the fleet is
 * busy" and "the button is broken" look identical from the outside. When the
 * refusal came from the fleet gate it also carries an override, because the
 * operator can know something the gate cannot — most often that the session
 * reported busy is the very one being used to ask for the swap, in which case
 * waiting for idle waits forever.
 *
 * The override lives here rather than on the button on purpose. Reaching it
 * requires having tried, been told which sessions are working, and then
 * choosing to interrupt them — a sequence, not a shortcut. A pre-emptive
 * confirmation dialog would have the opposite effect: a click to dismiss
 * before anyone knows whether there was anything to weigh.
 */
@Composable
fun ProviderSwapStatus(
    status: com.angussoftware.fueldashboard.presentation.SwitchRunStatus?,
    onOverride: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (status == null) return
    Column(modifier = modifier) {
        Text(
            text = (if (status.ok) "✓ " else "⚠ ") + status.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.ok) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (status.overridable && onOverride != null) {
            Spacer(Modifier.height(2.dp))
            // Same shape and metrics as the Swap button it stands in for, so
            // the pair reads as one control set rather than a button and a
            // link. Error colours rather than the default tonal ones: it does
            // the same thing, but over the top of a refusal.
            FilledTonalButton(
                onClick = onOverride,
                modifier = Modifier.heightIn(min = 28.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("Force swap", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
