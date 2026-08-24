package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueWheelPicker
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.timer.TimerFormat
import fr.kristenjestin.mue.ui.timer.TimerMessages
import fr.kristenjestin.mue.ui.timer.TimerTestTags

/** PRD FR-ACTIVITY-005 and FR-TIMER-006 share the ceiling: 99 h 59 min, hence 0..99 hours. */
private val HOURS: IntRange = 0..99
private val MINUTES: IntRange = 0 until ActivityDuration.SECONDS_PER_MINUTE
private val SECONDS: IntRange = 0 until ActivityDuration.SECONDS_PER_MINUTE

/**
 * The duration of a measured session (PRD FR-TIMER-006), summarised as `42 min 18 sec`.
 *
 * It is a summary and a panel rather than the two wheels of [ActivityDurationField], because a
 * timed duration arrives already correct: it was measured, and the form's job is to state it
 * and let it be corrected, not to ask for it. Three wheels standing open on a value nobody
 * needs to touch would also make the seconds look like a field to fill in.
 *
 * Manual entry is untouched and keeps its two wheels. That is FR-TIMER-006 in as many words —
 * only sessions from the timer show and correct seconds — and it is also why the third wheel
 * lives here rather than as an option on the shipped field, where a blank third box would
 * appear on every hand-typed session.
 */
@Composable
internal fun TimedDurationField(
    hours: String,
    minutes: String,
    seconds: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    val locale = rememberMueLocale()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        MuePickerField(
            label = LogActivityMessages.DURATION_LABEL,
            value = TimerFormat.reviewSummary(durationOf(hours, minutes, seconds), locale),
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TimerTestTags.DURATION_SUMMARY),
            onClickLabel = TimerMessages.EDIT_DURATION,
            // Named on the row as well as spoken, unlike the date beside it: a duration that
            // arrives already filled in has to say that it can still be argued with.
            trailingText = TimerMessages.EDIT_DURATION,
        )

        errorMessage?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.error,
                modifier = Modifier
                    .padding(horizontal = MueTheme.spacing.xs)
                    .semantics { error(message) },
            )
        }
    }
}

/**
 * The three-field correction of PRD FR-TIMER-006, in the panel the date and the start time
 * already rise in.
 *
 * The wheels hold their own value until `Use this duration`, exactly as the start-time dial
 * does: an abandoned panel leaves the measured duration alone, and one write reaches the draft
 * instead of one per detent — which is also one row rewritten in the database rather than
 * dozens (PRD 8.2).
 */
@Composable
internal fun TimedDurationSheet(
    visible: Boolean,
    hours: String,
    minutes: String,
    seconds: String,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
) {
    val haptics = rememberMueHaptics(hapticsEnabled)

    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = LogActivityMessages.DURATION_SHEET_TITLE,
        scrimContentDescription = LogActivityMessages.CLOSE_DURATION_SHEET,
    ) {
        // Re-keyed on every opening, so the panel always starts from what the form holds rather
        // than from a correction that was abandoned.
        key(visible, hours, minutes, seconds) {
            var pickedHours by remember { mutableIntStateOf(partOf(hours, HOURS)) }
            var pickedMinutes by remember { mutableIntStateOf(partOf(minutes, MINUTES)) }
            var pickedSeconds by remember { mutableIntStateOf(partOf(seconds, SECONDS)) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                TimedDurationWheel(
                    unit = LogActivityMessages.HOURS_UNIT,
                    value = pickedHours,
                    range = HOURS,
                    onValueChange = { pickedHours = it },
                    label = LogActivityMessages.DURATION_HOURS_LABEL,
                    stateDescriptionOf = LogActivityMessages::spokenHours,
                    testTag = ActivityTestTags.DURATION_HOURS_FIELD,
                    onHapticTick = haptics::tick,
                    modifier = Modifier.weight(1f),
                )
                TimedDurationWheel(
                    unit = LogActivityMessages.MINUTES_UNIT,
                    value = pickedMinutes,
                    range = MINUTES,
                    onValueChange = { pickedMinutes = it },
                    label = LogActivityMessages.DURATION_MINUTES_LABEL,
                    stateDescriptionOf = LogActivityMessages::spokenMinutes,
                    testTag = ActivityTestTags.DURATION_MINUTES_FIELD,
                    onHapticTick = haptics::tick,
                    modifier = Modifier.weight(1f),
                )
                TimedDurationWheel(
                    unit = TimerMessages.SECONDS_UNIT,
                    value = pickedSeconds,
                    range = SECONDS,
                    onValueChange = { pickedSeconds = it },
                    label = TimerMessages.DURATION_SECONDS_LABEL,
                    stateDescriptionOf = ::spokenSeconds,
                    testTag = TimerTestTags.DURATION_SECONDS_FIELD,
                    onHapticTick = haptics::tick,
                    modifier = Modifier.weight(1f),
                )
            }

            // No tag of its own: the panel holds one action and its label is stable, so a test
            // finds it the same way a reader does.
            MuePrimaryButton(
                label = LogActivityMessages.USE_THIS_DURATION,
                onClick = { onConfirm(pickedHours, pickedMinutes, pickedSeconds) },
            )
        }
    }
}

/**
 * One wheel and the unit under it, as the manual field draws them.
 *
 * It is written out again rather than shared with [ActivityDurationField]: that field ships,
 * is tested, and FR-TIMER-006 forbids changing the interface manual entry has. Thirty lines of
 * layout is a smaller price than a shared component with a mode switch running through it.
 */
@Composable
private fun TimedDurationWheel(
    unit: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: String,
    stateDescriptionOf: (Int) -> String,
    testTag: String,
    onHapticTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MueWheelPicker(
            value = value,
            range = range,
            onValueChange = onValueChange,
            label = label,
            stateDescriptionOf = stateDescriptionOf,
            modifier = Modifier.testTag(testTag),
            onHapticTick = onHapticTick,
        )
        MueText(
            text = unit,
            style = MueTheme.typography.label,
            color = MueTheme.colors.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MueTheme.spacing.xs),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * What the summary spells. The draft keeps the three parts as text, so a blank one is zero and
 * anything past the ceiling reads as it was typed until `Save activity` refuses it with
 * `ActivityValidation.TIMED_DURATION_ERROR` — the message belongs beside the action, not to a
 * value silently rewritten on screen.
 */
private fun durationOf(hours: String, minutes: String, seconds: String): ActivityDuration =
    ActivityDuration.ofHoursMinutesAndSecondsOrNull(
        hours = partOf(hours, HOURS),
        minutes = partOf(minutes, MINUTES),
        seconds = partOf(seconds, SECONDS),
    ) ?: ActivityDuration.ZERO

private fun partOf(raw: String, range: IntRange): Int =
    raw.toIntOrNull()?.coerceIn(range) ?: 0

/** The timer's own words, so the seconds are spoken exactly as the chronometer speaks them. */
private fun spokenSeconds(seconds: Int): String =
    "$seconds ${if (seconds == 1) TimerMessages.SECOND_UNIT else TimerMessages.SECONDS_UNIT}"
