package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueWheelPicker
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
import fr.kristenjestin.mue.ui.theme.MueTheme

/** PRD FR-ACTIVITY-005: up to 99 h 59 m, so the hours wheel stops at ninety-nine. */
private val HOURS: IntRange = 0..99
private val MINUTES: IntRange = 0 until ActivityDuration.SECONDS_PER_MINUTE

/**
 * The duration of a session, on two wheels (PRD FR-ACTIVITY-005).
 *
 * One field, used by `Log activity` and by the strength editor alike, because the two write the
 * same draft value (PRD 9.1) and one value entered two different ways is worse than either.
 *
 * The keyboard it replaces was the wrong instrument for the job — a duration is chosen from a
 * short scale, not composed digit by digit — and Material's own answer is worse still: its time
 * picker is a clock dial, which reads *half past midnight* where the person means *thirty
 * minutes*. `MueWheelPicker` is the ruler's family instead, which is the interaction the product
 * is already built around.
 *
 * The draft keeps the two parts as text, so a blank one is a zero here and the wheel starts on
 * `0 h 0 min`. That is not a value being invented: a duration is required, `0:00` is refused by
 * `ActivityValidation` with the range in the message, and offering `1 min` instead would let a
 * forgotten field save a session nobody logged.
 */
@Composable
internal fun ActivityDurationField(
    hours: String,
    minutes: String,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    hapticsEnabled: Boolean = true,
) {
    val haptics = rememberMueHaptics(hapticsEnabled)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        MueSurfaceCard(shape = MueTheme.shapes.field) {
            LabelWithIcon(LogActivityMessages.DURATION_LABEL, ActivityIcons.TIMER)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MueTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                DurationWheel(
                    unit = LogActivityMessages.HOURS_UNIT,
                    value = hours.toIntOrNull()?.coerceIn(HOURS) ?: 0,
                    range = HOURS,
                    onValueChange = { onHoursChange(it.toString()) },
                    label = LogActivityMessages.DURATION_HOURS_LABEL,
                    stateDescriptionOf = LogActivityMessages::spokenHours,
                    testTag = ActivityTestTags.DURATION_HOURS_FIELD,
                    onHapticTick = haptics::tick,
                    modifier = Modifier.weight(1f),
                )
                DurationWheel(
                    unit = LogActivityMessages.MINUTES_UNIT,
                    value = minutes.toIntOrNull()?.coerceIn(MINUTES) ?: 0,
                    range = MINUTES,
                    onValueChange = { onMinutesChange(it.toString()) },
                    label = LogActivityMessages.DURATION_MINUTES_LABEL,
                    stateDescriptionOf = LogActivityMessages::spokenMinutes,
                    testTag = ActivityTestTags.DURATION_MINUTES_FIELD,
                    onHapticTick = haptics::tick,
                    modifier = Modifier.weight(1f),
                )
            }
        }

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
 * One wheel and the unit under it.
 *
 * The unit is a caption rather than part of every row: repeating `h` down five rows says the
 * same thing five times and leaves less room for the digits, which are what is being aimed at.
 * The spoken value carries the word instead, so nothing is lost to a screen reader.
 */
@Composable
private fun DurationWheel(
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
