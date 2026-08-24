package fr.kristenjestin.mue.ui.activity

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/** PRD FR-ACTIVITY-011. */
internal const val DELETE_CONFIRMATION_TITLE: String = "Delete this activity?"
internal const val DELETE_CONFIRMATION_BODY: String =
    "Its measurements, equipment, exercises and sets go with it."
internal const val DELETE_CONFIRM: String = "Delete"
internal const val CANCEL: String = "Cancel"

/** PRD 9.1, the module's only other confirmation. */
internal const val QUICK_LOG_CONFIRMATION_TITLE: String = "Switch to quick log?"
internal const val QUICK_LOG_CONFIRM: String = "Switch"

/** `Your 3 exercises will be removed.` — the count is what makes the warning worth reading. */
internal fun quickLogConfirmationBody(exercises: Int): String =
    "Your ${LogActivityMessages.exerciseCount(exercises)} will be removed."

/**
 * The two moments the form stops and asks (PRD FR-ACTIVITY-011 and PRD 9.1).
 *
 * Both are Material `AlertDialog`s. The deletion one is the base PRD's second documented
 * Material exception — BR-006 wants a modal, blocking confirmation and Material's is already
 * focus-trapped, dismissible and announced. The quick-log one destroys written data in exactly
 * the same way, so it borrows the same shape rather than inventing a second kind of question.
 */
@Composable
internal fun ActivityConfirmations(state: LogActivityUiState, actions: LogActivityActions) {
    if (state.deleteConfirmationVisible) {
        ConfirmationDialog(
            title = DELETE_CONFIRMATION_TITLE,
            body = DELETE_CONFIRMATION_BODY,
            confirmLabel = DELETE_CONFIRM,
            destructive = true,
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }

    if (state.quickLogConfirmationVisible) {
        ConfirmationDialog(
            title = QUICK_LOG_CONFIRMATION_TITLE,
            body = quickLogConfirmationBody(state.storedExerciseCount),
            confirmLabel = QUICK_LOG_CONFIRM,
            destructive = true,
            onConfirm = actions.onConfirmQuickLog,
            onDismiss = actions.onCancelQuickLog,
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MueText(title, MueTheme.typography.sectionTitle) },
        text = { MueText(body, MueTheme.typography.body, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                MueText(
                    text = confirmLabel,
                    style = MueTheme.typography.button,
                    color = if (destructive) colors.error else colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                MueText(CANCEL, MueTheme.typography.button, color = colors.textSecondary)
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}
