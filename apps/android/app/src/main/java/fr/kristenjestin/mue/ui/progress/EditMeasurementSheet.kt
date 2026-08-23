package fr.kristenjestin.mue.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

internal const val EDIT_SHEET_TITLE = "Edit measurement"
internal const val SAVE_CHANGES = "Save changes"
internal const val DELETE_MEASUREMENT = "Delete measurement"
internal const val DELETE_CONFIRMATION_TITLE = "Delete this measurement?"
internal const val DELETE_CONFIRMATION_BODY =
    "It will be removed from your history for good."
internal const val DELETE_CONFIRM = "Delete"
internal const val CANCEL = "Cancel"
internal const val DATE_LABEL = "Date"
internal const val WEIGHT_LABEL = "Weight"
internal const val CHANGE_DATE = "Change"

/** Everything the edit panel can ask the ViewModel to do (PRD FR-PROGRESS-005 and 006). */
@Stable
internal class ProgressEditorActions(
    val onDismiss: () -> Unit = {},
    val onWeightChange: (String) -> Unit = {},
    val onOpenDatePicker: () -> Unit = {},
    val onDismissDatePicker: () -> Unit = {},
    val onDateChange: (LocalDate) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
)

/**
 * The edit and delete panel, plus the two Material dialogs PRD 12.1 allows: the date picker
 * and the deletion confirmation required by BR-006.
 *
 * The last non-null [editor] is kept so the sheet still has something to draw while it
 * animates out after a save or a delete.
 */
@Composable
internal fun EditMeasurementSheet(
    editor: EditorUiState?,
    today: LocalDate,
    actions: ProgressEditorActions,
) {
    var lastEditor by remember { mutableStateOf(editor) }
    if (editor != null) lastEditor = editor
    val shown = lastEditor

    MueBottomSheet(
        visible = editor != null,
        onDismissRequest = actions.onDismiss,
        title = EDIT_SHEET_TITLE,
    ) {
        if (shown != null) EditMeasurementPanel(shown, today, actions)
    }

    if (editor?.datePickerVisible == true) {
        EditDatePickerDialog(selected = editor.date, today = today, actions = actions)
    }

    if (editor?.deleteConfirmationVisible == true) {
        DeleteConfirmationDialog(actions)
    }
}

/** The body of the sheet, hoisted so it can be previewed without a dialog window. */
@Composable
internal fun ColumnScope.EditMeasurementPanel(
    editor: EditorUiState,
    today: LocalDate,
    actions: ProgressEditorActions,
) {
    MuePickerField(
        label = DATE_LABEL,
        value = ProgressFormat.dateOrToday(editor.date, today),
        trailingText = CHANGE_DATE,
        onClick = actions.onOpenDatePicker,
        onClickLabel = "Change the measurement date",
    )

    MueTextField(
        label = WEIGHT_LABEL,
        value = editor.weightInput,
        onValueChange = actions.onWeightChange,
        suffix = "kg",
        errorMessage = editor.weightError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
    )

    MuePrimaryButton(
        label = SAVE_CHANGES,
        onClick = actions.onSave,
        modifier = Modifier.padding(top = MueTheme.spacing.xs),
    )

    MueSecondaryButton(
        label = DELETE_MEASUREMENT,
        onClick = actions.onRequestDelete,
        contentColor = MueTheme.colors.error,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDatePickerDialog(
    selected: LocalDate,
    today: LocalDate,
    actions: ProgressEditorActions,
) {
    // PRD BR-009: nothing after today is offered in the first place.
    val selectableDates = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis.toLocalDate() <= today

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.toEpochDay() * MILLIS_PER_DAY,
        selectableDates = selectableDates,
    )

    DatePickerDialog(
        onDismissRequest = actions.onDismissDatePicker,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis == null) {
                        actions.onDismissDatePicker()
                    } else {
                        actions.onDateChange(millis.toLocalDate())
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDatePicker) { Text(CANCEL) }
        },
        colors = DatePickerDefaults.colors(
            containerColor = MueTheme.colors.canvasElevated,
        ),
    ) {
        DatePicker(state = pickerState, showModeToggle = false)
    }
}

@Composable
private fun DeleteConfirmationDialog(actions: ProgressEditorActions) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = actions.onCancelDelete,
        title = { MueText(DELETE_CONFIRMATION_TITLE, MueTheme.typography.sectionTitle) },
        text = {
            MueText(
                DELETE_CONFIRMATION_BODY,
                MueTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmDelete) {
                MueText(DELETE_CONFIRM, MueTheme.typography.button, color = colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDelete) {
                MueText(CANCEL, MueTheme.typography.button, color = colors.textSecondary)
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}

/** The picker speaks UTC milliseconds; the app only ever deals in local calendar days. */
private fun Long.toLocalDate(): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))

@Preview(name = "Edit panel", showBackground = true, backgroundColor = 0xFF17171B)
@Composable
private fun EditMeasurementPanelPreview() {
    MueTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
        ) {
            MueText(EDIT_SHEET_TITLE, MueTheme.typography.sectionTitle)
            EditMeasurementPanel(
                editor = EditorUiState(
                    originalDate = LocalDate.of(2026, 8, 18),
                    date = LocalDate.of(2026, 8, 18),
                    weightInput = "74.90",
                    weightError = null,
                    datePickerVisible = false,
                    deleteConfirmationVisible = false,
                ),
                today = LocalDate.of(2026, 8, 23),
                actions = ProgressEditorActions(),
            )
        }
    }
}
