package fr.kristenjestin.mue.ui.timer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * FR-TIMER-009: `Discard timer` never destroys a measured duration without being asked twice.
 *
 * The shipped Material `AlertDialog` of `DeleteActivityDialog`, not the prototype's bottom
 * sheet: this is the same question the module already asks about a stored session — modal,
 * blocking, focus-trapped and announced — and a second shape for it would be a second thing to
 * keep accessible. The prototype's third clause, `This cannot be undone.`, is dropped because
 * FR-TIMER-009 spells the message out and does not carry it.
 *
 * A draft that has already stopped asks the other question of FR-TIMER-009: nothing about it is
 * a *timer* any more, so it is named as the draft it is.
 */
@Composable
internal fun DiscardTimerDialog(
    status: TimedDraftStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TimerTestTags.DISCARD_DIALOG),
        title = {
            MueText(
                text = if (status == TimedDraftStatus.PENDING_REVIEW) {
                    TimerMessages.DISCARD_DRAFT_TITLE
                } else {
                    TimerMessages.DISCARD_TIMER_TITLE
                },
                style = MueTheme.typography.sectionTitle,
            )
        },
        text = {
            MueText(
                text = TimerMessages.DISCARD_TIMER_BODY,
                style = MueTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TimerTestTags.CONFIRM_DISCARD),
            ) {
                MueText(TimerMessages.DISCARD, MueTheme.typography.button, color = colors.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TimerTestTags.KEEP_TIMER),
            ) {
                MueText(
                    text = TimerMessages.KEEP_TIMER,
                    style = MueTheme.typography.button,
                    color = colors.textSecondary,
                )
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}
