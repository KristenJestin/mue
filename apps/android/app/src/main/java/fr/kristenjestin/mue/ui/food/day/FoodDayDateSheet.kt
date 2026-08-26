package fr.kristenjestin.mue.ui.food.day

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
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueBottomSheetDefaults
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Jumping to a day that is not next door (PRD_FOOD 10.1).
 *
 * The two arrows walk the week; the calendar is how a Tuesday three weeks back is reached
 * without twenty taps. It is the same panel `ActivityDateSheet` raises — Material's own grid,
 * dressed in the product's colours, as the base PRD 12.1 allows for the V1 — so the module with
 * two date pickers is not also the module where they look like different products.
 *
 * The rule it enforces is [FoodDayUiState.isReachable]'s: a day is offered when the journal will
 * take it (PRD_FOOD 22) **or** when a proposal may be posed on it (PRD_FOOD 12 and 15). It used to
 * ask `FoodLogEntry.isLoggableOn` alone, which is the journal's ceiling, so every day after today
 * was greyed out in the grid and the whole planning half of the module had no door — the arrows,
 * this calendar and the ViewModel all refused the same days for the same wrong reason.
 *
 * A day beyond both rules is never *offered*, in the grid or in the year list, rather than refused
 * after the fact, and it is the one predicate the screen uses everywhere so the calendar, the
 * arrows and the storage cannot disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodDayDateSheet(
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
        modifier = modifier.testTag(FoodTestTags.DAY_DATE_PICKER),
        title = FoodDayMessages.DATE_SHEET_TITLE,
        scrimContentDescription = FoodDayMessages.CLOSE_DATE_SHEET,
        // The calendar carries its own inner padding; the full screen gutter on top of it
        // squeezes the 48 dp day cells.
        contentPadding = MueBottomSheetDefaults.contentPadding(horizontal = MueTheme.spacing.lg),
    ) {
        // Re-keyed on every opening so the calendar always reappears on the day being viewed
        // rather than on the last choice that was abandoned.
        key(visible, selected) {
            val selectableDates = remember(today) {
                // The furthest day either rule allows, which is where the year list has to stop.
                val furthest = today.plusDays(MealPlanEntry.MAX_DAYS_AHEAD)
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        FoodDayUiState.isReachable(utcTimeMillis.toUtcLocalDate(), today)

                    override fun isSelectableYear(year: Int): Boolean = year <= furthest.year
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
                label = FoodDayMessages.USE_THIS_DAY,
                onClick = {
                    val picked = pickerState.selectedDateMillis?.toUtcLocalDate() ?: selected
                    onConfirm(if (FoodDayUiState.isReachable(picked, today)) picked else today)
                },
            )
        }
    }
}

/**
 * The Material picker speaks in UTC milliseconds while Mue stores pure local dates (PRD_FOOD
 * 10.1: "une vraie date locale, jamais un index"). Both conversions pin the zone to UTC so no
 * timezone can shift a day.
 */
private fun LocalDate.toUtcMillis(): Long = toEpochDay() * MILLIS_PER_DAY

private fun Long.toUtcLocalDate(): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))
