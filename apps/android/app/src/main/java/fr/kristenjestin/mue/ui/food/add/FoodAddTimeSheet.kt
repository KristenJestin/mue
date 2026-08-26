package fr.kristenjestin.mue.ui.food.add

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueBottomSheetDefaults
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalTime

/**
 * The hour a line was eaten at (PRD_FOOD 10.3), in the panel the rest of the app picks a time in.
 *
 * The same documented Material exception `ActivityTimeSheet` records: a clock dial is a dense,
 * fully accessible control that already knows about 12- and 24-hour phones, and rebuilding it
 * would buy a look and cost every one of those behaviours. Only its colours are the product's.
 *
 * It is a sibling of that sheet rather than a reuse of it, for one reason each way: a meal's time
 * is **never optional** — PRD_FOOD 8.4 puts an hour on every line — so there is no `Clear` here,
 * and the words belong to this module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodAddTimeSheet(
    visible: Boolean,
    selected: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = FoodAddMessages.TIME_SHEET_TITLE,
        scrimContentDescription = FoodAddMessages.CLOSE_TIME_SHEET,
        // The dial carries its own inner padding; the full screen gutter squeezes its numbers.
        contentPadding = MueBottomSheetDefaults.contentPadding(horizontal = MueTheme.spacing.lg),
    ) {
        // Re-keyed on every opening, so the dial always reappears on the stored time rather than
        // on a choice that was abandoned.
        key(visible, selected) {
            val pickerState = rememberTimePickerState(
                initialHour = selected.hour,
                initialMinute = selected.minute,
            )

            Box(
                modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.TIME_PICKER),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(
                    state = pickerState,
                    colors = TimePickerDefaults.colors(
                        containerColor = MueTheme.colors.canvasElevated,
                        clockDialColor = MueTheme.colors.surface,
                        clockDialSelectedContentColor = MueTheme.colors.onAccent,
                        clockDialUnselectedContentColor = MueTheme.colors.textPrimary,
                        selectorColor = MueTheme.colors.accent,
                        periodSelectorBorderColor = MueTheme.colors.surfaceBorder,
                        periodSelectorSelectedContainerColor = MueTheme.colors.accentSoft,
                        periodSelectorUnselectedContainerColor = MueTheme.colors.surface,
                        periodSelectorSelectedContentColor = MueTheme.colors.onAccentSoft,
                        periodSelectorUnselectedContentColor = MueTheme.colors.textTertiary,
                        timeSelectorSelectedContainerColor = MueTheme.colors.accentSoft,
                        timeSelectorUnselectedContainerColor = MueTheme.colors.surface,
                        timeSelectorSelectedContentColor = MueTheme.colors.onAccentSoft,
                        timeSelectorUnselectedContentColor = MueTheme.colors.textPrimary,
                    ),
                )
            }

            MuePrimaryButton(
                label = FoodAddMessages.USE_THIS_TIME,
                onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
