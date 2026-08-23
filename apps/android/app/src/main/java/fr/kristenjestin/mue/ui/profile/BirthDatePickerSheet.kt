package fr.kristenjestin.mue.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueBottomSheetDefaults
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The Material date picker inside the Mue bottom sheet, skinned with the product colours —
 * the exception PRD 12.1 grants for the V1.
 *
 * The 120-year floor and the "no future date" rule of FR-PROFILE-002 are enforced here as
 * well as at validation time, so the user is never offered a date that would be refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDatePickerSheet(
    visible: Boolean,
    initialDate: LocalDate?,
    today: LocalDate,
    onDismissRequest: () -> Unit,
    onConfirm: (LocalDate?) -> Unit,
) {
    val earliest = remember(today) { today.minusYears(UserProfile.MAX_AGE_YEARS) }
    val selectableDates = remember(today, earliest) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = utcTimeMillis.toUtcLocalDate()
                return !date.isAfter(today) && !date.isBefore(earliest)
            }

            override fun isSelectableYear(year: Int): Boolean = year in earliest.year..today.year
        }
    }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.toUtcMillis(),
        initialDisplayedMonthMillis = (initialDate ?: earliest.plusYears(90))
            .withDayOfMonth(1)
            .toUtcMillis(),
        yearRange = earliest.year..today.year,
        selectableDates = selectableDates,
    )

    // Reopening the sheet must show the stored date again, not the last abandoned choice.
    LaunchedEffect(visible, initialDate) {
        if (visible) {
            pickerState.selectedDateMillis = initialDate?.toUtcMillis()
        }
    }

    val locale = rememberProfileLocale()
    val selected = pickerState.selectedDateMillis?.toUtcLocalDate()

    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = "Date of birth",
        scrimContentDescription = "Close the date of birth picker",
        // The calendar carries its own inner padding; the full screen gutter on top of it
        // squeezes the 48 dp day cells.
        contentPadding = MueBottomSheetDefaults.contentPadding(
            horizontal = MueTheme.spacing.lg,
        ),
    ) {
        MueText(
            text = selected?.let { formatBirthDate(it, locale) } ?: "No date selected",
            style = MueTheme.typography.bodyStrong,
            color = MueTheme.colors.accent,
            maxLines = 1,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            if (initialDate != null) {
                MueSecondaryButton(
                    label = "Remove",
                    onClick = { onConfirm(null) },
                    modifier = Modifier.weight(1f),
                )
            }
            MuePrimaryButton(
                label = "Use this date",
                onClick = { selected?.let(onConfirm) },
                modifier = Modifier.weight(1f),
                enabled = selected != null,
            )
        }
    }
}

private fun LocalDate.toUtcMillis(): Long = toEpochDay() * MILLIS_PER_DAY

/** The Material picker speaks in UTC milliseconds; Mue only ever stores a pure local date. */
private fun Long.toUtcLocalDate(): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))
