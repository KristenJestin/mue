package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueBottomSheetDefaults
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * The optional start time of PRD 8.2, in the same panel the date rises in.
 *
 * **A fourth documented Material exception**, alongside the base PRD 12.1 trio of the date
 * picker, the delete dialog and the effort slider. The reasoning is the date picker's, word for
 * word: a clock dial is a dense, fully accessible control that already knows about 12- and
 * 24-hour phones, and rebuilding it would buy a look and cost every one of those behaviours.
 * As with the calendar, only its colours are the product's — every one of the fourteen is
 * overridden here — and it lives inside `MueBottomSheet` so it arrives the way the rest of the
 * app arrives. This should be recorded in PRD 12.1 next to the other three.
 *
 * The time stays optional, which a picker on its own cannot express: [onConfirm] is called with
 * null by `Clear`, so `no start time` remains reachable and remains distinct from a real 00:00.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityTimeSheet(
    visible: Boolean,
    selected: LocalTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = rememberMueLocale()

    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = LogActivityMessages.TIME_SHEET_TITLE,
        scrimContentDescription = LogActivityMessages.CLOSE_TIME_SHEET,
        // The dial carries its own inner padding, exactly as the calendar does, and the full
        // screen gutter on top of it squeezes the 48 dp numbers.
        contentPadding = MueBottomSheetDefaults.contentPadding(horizontal = MueTheme.spacing.lg),
    ) {
        // Re-keyed on every opening so the dial always reappears on the stored time rather than
        // on the last choice that was abandoned.
        key(visible, selected) {
            /*
             * A dial has to open somewhere, and 00:00 is the one place it must not: PRD 12
             * forbids presenting a missing optional value as a plausible zero, and midnight is
             * a real session start. An unset time therefore opens on the current minute — a
             * proposal, since nothing is written until `Use this time` is pressed.
             */
            val opening = remember { selected ?: LocalTime.now().truncatedTo(ChronoUnit.MINUTES) }
            val pickerState = rememberTimePickerState(
                initialHour = opening.hour,
                initialMinute = opening.minute,
            )

            MueText(
                text = LogActivityFormat.startTime(selected, locale),
                style = MueTheme.typography.bodyStrong,
                color = if (selected == null) {
                    MueTheme.colors.textTertiary
                } else {
                    MueTheme.colors.accent
                },
                maxLines = 1,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ActivityTestTags.START_TIME_PICKER),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                // Offered only when there is something to take back off, as `Remove` is on the
                // date of birth: a `Clear` on an empty field claims a state that already holds.
                if (selected != null) {
                    MueSecondaryButton(
                        label = LogActivityMessages.CLEAR_START_TIME,
                        onClick = { onConfirm(null) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(ActivityTestTags.CLEAR_START_TIME),
                    )
                }
                MuePrimaryButton(
                    label = LogActivityMessages.USE_THIS_TIME,
                    onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ActivityTestTags.CONFIRM_START_TIME),
                )
            }
        }
    }
}
