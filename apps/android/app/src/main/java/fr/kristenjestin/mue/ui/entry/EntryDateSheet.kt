package fr.kristenjestin.mue.ui.entry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date picker of PRD FR-ENTRY-005, in the panel that rises from the bottom.
 *
 * PRD 12.1 keeps Material's own calendar for the V1 and only dresses it in the product's
 * colours, so this file is thin on purpose. The single rule it enforces itself is BR-009:
 * no day after today can be picked, in the grid or in the year list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDateSheet(
    visible: Boolean,
    selected: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = "Measurement date",
        scrimContentDescription = "Close the date picker",
    ) {
        // Re-keyed on every opening so the calendar always reappears on the current date.
        key(visible, selected) {
            val selectableDates = remember(today) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !utcTimeMillis.toUtcLocalDate().isAfter(today)

                    override fun isSelectableYear(year: Int): Boolean = year <= today.year
                }
            }
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = selected.toUtcMillis(),
                initialDisplayedMonthMillis = selected.withDayOfMonth(1).toUtcMillis(),
                selectableDates = selectableDates,
            )

            DatePicker(
                state = pickerState,
                modifier = Modifier.fillMaxWidth(),
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = MueTheme.colors.canvasElevated,
                ),
            )

            MuePrimaryButton(
                label = "Done",
                onClick = {
                    val picked = pickerState.selectedDateMillis?.toUtcLocalDate() ?: selected
                    onConfirm(if (picked.isAfter(today)) today else picked)
                },
            )
        }
    }
}

/**
 * The Material picker speaks in UTC milliseconds while Mue stores pure local dates
 * (PRD 11.1). Both conversions pin the zone to UTC so no timezone can shift a day.
 */
private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
