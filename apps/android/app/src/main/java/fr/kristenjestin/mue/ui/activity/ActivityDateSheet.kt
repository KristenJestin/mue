package fr.kristenjestin.mue.ui.activity

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
import fr.kristenjestin.mue.ui.components.MueBottomSheetDefaults
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The date of a session (PRD FR-ACTIVITY-005), in the panel that rises from the bottom.
 *
 * The base PRD's 12.1 admits Material's own calendar for the V1, dressed in the product's
 * colours; `EntryDateSheet` and `BirthDatePickerSheet` already read that way. This screen used
 * to raise a bare `DatePickerDialog` instead — a centred Material box with its own greys, its
 * own `Select date` headline and its own buttons — which made the one module with two date
 * pickers also the one module where they looked like different products.
 *
 * The single rule enforced here is FR-ACTIVITY-005's: a future day is never offered, in the
 * grid or in the year list, rather than refused after the fact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityDateSheet(
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
        title = LogActivityMessages.DATE_SHEET_TITLE,
        scrimContentDescription = LogActivityMessages.CLOSE_DATE_SHEET,
        // The calendar carries its own inner padding; the full screen gutter on top of it
        // squeezes the 48 dp day cells.
        contentPadding = MueBottomSheetDefaults.contentPadding(horizontal = MueTheme.spacing.lg),
    ) {
        // Re-keyed on every opening so the calendar always reappears on the stored date rather
        // than on the last choice that was abandoned.
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
                    weekdayContentColor = MueTheme.colors.textTertiary,
                    subheadContentColor = MueTheme.colors.textSecondary,
                    navigationContentColor = MueTheme.colors.textSecondary,
                    yearContentColor = MueTheme.colors.textPrimary,
                    currentYearContentColor = MueTheme.colors.accent,
                    selectedYearContentColor = MueTheme.colors.onAccent,
                    selectedYearContainerColor = MueTheme.colors.accent,
                    dayContentColor = MueTheme.colors.textPrimary,
                    disabledDayContentColor = MueTheme.colors.textQuiet,
                    selectedDayContentColor = MueTheme.colors.onAccent,
                    selectedDayContainerColor = MueTheme.colors.accent,
                    todayContentColor = MueTheme.colors.accent,
                    todayDateBorderColor = MueTheme.colors.accent,
                    dividerColor = MueTheme.colors.hairline,
                ),
            )

            MuePrimaryButton(
                label = LogActivityMessages.USE_THIS_DATE,
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
private fun LocalDate.toUtcMillis(): Long = toEpochDay() * MILLIS_PER_DAY

private fun Long.toUtcLocalDate(): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))
