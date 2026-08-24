package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChoiceCardDefaults
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueChipRow
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueFieldContainer
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueRemovableChip
import fr.kristenjestin.mue.ui.components.MueSegmentedChoice
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.theme.MueTheme

/** What a two-part clock box shows before anything is typed; never a plausible `0` (PRD 12). */
internal const val EMPTY_NUMBER_HINT: String = "--"

/** Two digits, plus the room the caret needs. */
private val ClockBoxWidth: Dp = 44.dp

/**
 * The pace is the one two-part field that shares a row with a neighbour, so its boxes give up
 * what the `/km` suffix needs. Measured on a 390 dp screen: anything wider ellipsises the unit.
 */
private val PaceBoxWidth: Dp = 30.dp

/** A small box still has to be reachable; the container around it is 64 dp tall (PRD 15). */
private val ClockBoxHeight: Dp = 36.dp

private val MetricIconSize: Dp = 16.dp

/**
 * The part of the form the preset decides (PRD FR-ACTIVITY-004 to 008).
 *
 * Everything here is optional except the builder's own activity: PRD FR-ACTIVITY-006 and 007
 * make every measurement a value the person may simply not have. Nothing is prefilled and no
 * empty field is shown as a zero.
 */
@Composable
internal fun ActivityPresetForm(
    state: LogActivityUiState,
    actions: LogActivityActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        if (state.showsBuilder) ActivityBuilder(state, actions)
        if (state.showsStrengthDetail) StrengthDetailChoice(state, actions)
        MetricFields(state, actions)
    }
}

// region Measurements

@Composable
private fun MetricFields(state: LogActivityUiState, actions: LogActivityActions) {
    if (state.metrics.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        // A single optional box does not need a section around it; four of them do.
        if (state.metrics.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MueTheme.spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MueText(
                    text = LogActivityMessages.detailsTitle(state.preset),
                    style = MueTheme.typography.sectionTitle,
                    maxLines = 1,
                )
                MueValueChip(LogActivityMessages.OPTIONAL_BADGE)
            }
        }

        state.metrics.chunked(COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                row.forEach { metric ->
                    MetricField(metric, actions, Modifier.weight(1f))
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MetricField(
    metric: MetricFieldState,
    actions: LogActivityActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        if (metric.isPace) {
            // PRD FR-ACTIVITY-007: `7:10 /km` is one stored value typed in two boxes, because
            // no numeric keyboard offers a colon.
            TwoPartNumberField(
                label = metric.kind.label,
                icon = ActivityIcons.forMetric(metric.kind),
                first = ClockPart(
                    value = metric.paceMinutes,
                    onValueChange = { actions.onPaceChange(it, null) },
                    suffix = ":",
                    contentDescription = "${metric.kind.label} minutes",
                    testTag = ActivityTestTags.metricField(metric.kind.id),
                ),
                second = ClockPart(
                    value = metric.paceSeconds,
                    onValueChange = { actions.onPaceChange(null, it) },
                    suffix = metric.kind.displayUnit,
                    contentDescription = "${metric.kind.label} seconds",
                    testTag = "${ActivityTestTags.metricField(metric.kind.id)}:seconds",
                ),
                errorMessage = metric.error,
                boxWidth = PaceBoxWidth,
            )
        } else {
            MueTextField(
                label = metric.kind.label,
                value = metric.input,
                onValueChange = { actions.onMetricChange(metric.kind, it) },
                modifier = Modifier.testTag(ActivityTestTags.metricField(metric.kind.id)),
                placeholder = EMPTY_NUMBER_HINT,
                suffix = metric.kind.displayUnit,
                errorMessage = metric.error,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (metric.kind.displayDecimals == 0) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Decimal
                    },
                ),
                trailing = {
                    MueIcon(
                        iconName = ActivityIcons.forMetric(metric.kind),
                        tint = MueTheme.colors.accent,
                        size = MetricIconSize,
                    )
                },
            )
        }

        // PRD 11.3: an estimation keeps the provenance of the machine that produced it.
        if (metric.source == MetricSource.EQUIPMENT) {
            MueText(
                text = LogActivityMessages.FROM_EQUIPMENT,
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textQuiet,
                modifier = Modifier.padding(start = MueTheme.spacing.xs),
                maxLines = 1,
            )
        }
    }
}

// endregion

// region The `Other` builder

@Composable
private fun ActivityBuilder(state: LogActivityUiState, actions: LogActivityActions) {
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MueTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(LogActivityMessages.BUILDER_TITLE, MueTheme.typography.sectionTitle)
                MueText(
                    text = LogActivityMessages.BUILDER_SUBTITLE,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                )
            }
            MueIcon(ActivityIcons.SPARKLES, tint = MueTheme.colors.accent)
        }

        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs)) {
            MueFieldContainer(
                label = LogActivityMessages.MAIN_ACTIVITY_LABEL,
                modifier = Modifier
                    .testTag(ActivityTestTags.MOVEMENT_PICKER)
                    .heightIn(min = MueTheme.spacing.xxxl),
                isError = state.movementError != null,
                onClick = actions.onOpenMovementPicker,
                onClickLabel = LogActivityMessages.ACTIVITY_PICKER_TITLE,
                trailing = {
                    MueIcon(MueIcons.CHEVRON_RIGHT, tint = MueTheme.colors.textTertiary)
                },
            ) {
                MueText(
                    text = state.mainActivityLabel,
                    style = MueTheme.typography.bodyStrong,
                    color = if (state.hasMainActivity) {
                        MueTheme.colors.textPrimary
                    } else {
                        MueTheme.colors.textTertiary
                    },
                    maxLines = 1,
                )
            }
            state.movementError?.let { message ->
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

        MueSurfaceCard(shape = MueTheme.shapes.field) {
            LabelWithIcon(LogActivityMessages.ENVIRONMENT_LABEL, ActivityIcons.MAP_PIN)
            MueSegmentedChoice(
                options = ENVIRONMENTS,
                selected = state.environment,
                onSelect = actions.onEnvironmentSelected,
                label = { it.displayName },
                modifier = Modifier
                    .padding(top = MueTheme.spacing.md)
                    .testTag(ActivityTestTags.ENVIRONMENT_PICKER),
            )
        }

        MueSurfaceCard(shape = MueTheme.shapes.field) {
            LabelWithIcon(LogActivityMessages.EQUIPMENT_LABEL, ActivityIcons.WRENCH)
            if (state.equipment.isNotEmpty()) {
                MueChipRow(modifier = Modifier.padding(top = MueTheme.spacing.md)) {
                    state.equipment.forEach { chip ->
                        MueRemovableChip(
                            label = chip.label,
                            onRemove = { actions.onEquipmentRemoved(chip.index) },
                            modifier = Modifier.testTag(
                                ActivityTestTags.equipmentChip(chip.index),
                            ),
                        )
                    }
                }
            }
            MueDashedAction(
                label = if (state.equipment.isEmpty()) {
                    LogActivityMessages.CHOOSE_EQUIPMENT
                } else {
                    LogActivityMessages.ADD_ANOTHER_EQUIPMENT
                },
                onClick = actions.onOpenEquipmentPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MueTheme.spacing.md)
                    .testTag(ActivityTestTags.EQUIPMENT_PICKER),
                icon = { MueIcon(ActivityIcons.PLUS, size = MetricIconSize) },
            )
        }
    }
}

// endregion

// region Quick and detailed strength logging

@Composable
private fun StrengthDetailChoice(state: LogActivityUiState, actions: LogActivityActions) {
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        Column(modifier = Modifier.padding(horizontal = MueTheme.spacing.sm)) {
            MueText(LogActivityMessages.DETAIL_TITLE, MueTheme.typography.sectionTitle)
            MueText(
                text = LogActivityMessages.DETAIL_SUBTITLE,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textTertiary,
            )
        }
        MueChoiceRow {
            MueChoiceCard(
                label = LogActivityMessages.QUICK_LOG,
                description = LogActivityMessages.QUICK_LOG_DESCRIPTION,
                selected = !state.detailed,
                onClick = actions.onQuickLog,
                icon = { MueIcon(MueIcons.ZAP, tint = MueTheme.colors.accent) },
                minHeight = MueChoiceCardDefaults.TallHeight,
                contentPadding = MueChoiceCardDefaults.TallPadding,
                labelStyle = MueChoiceCardDefaults.tallLabelStyle(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivityTestTags.QUICK_LOG),
            )
            MueChoiceCard(
                label = LogActivityMessages.DETAILED_LOG,
                description = if (state.exerciseCount > 0) {
                    LogActivityMessages.exerciseCount(state.exerciseCount)
                } else {
                    LogActivityMessages.DETAILED_LOG_DESCRIPTION
                },
                selected = state.detailed,
                onClick = actions.onDetailedLog,
                icon = { MueIcon(MueIcons.LIST_PLUS, tint = MueTheme.colors.accent) },
                minHeight = MueChoiceCardDefaults.TallHeight,
                contentPadding = MueChoiceCardDefaults.TallPadding,
                labelStyle = MueChoiceCardDefaults.tallLabelStyle(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivityTestTags.DETAILED_LOG),
            )
        }
    }
}

// endregion

// region Shared field parts

/** One box of a two-part field, with everything a caller has to say about it. */
internal class ClockPart(
    val value: String,
    val onValueChange: (String) -> Unit,
    val suffix: String,
    val contentDescription: String,
    val testTag: String,
)

/**
 * A field holding two small numbers — `1 h 45 min`, `18:30`, `7:10 /km`.
 *
 * Three of the form's values are pairs, so the shape is written once. It composes
 * [MueFieldContainer] rather than forking [MueTextField]: only the inside of the container
 * differs, and the border, the label and the error treatment stay the design system's.
 */
@Composable
internal fun TwoPartNumberField(
    label: String,
    first: ClockPart,
    second: ClockPart,
    modifier: Modifier = Modifier,
    icon: String? = null,
    errorMessage: String? = null,
    boxWidth: Dp = ClockBoxWidth,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        MueFieldContainer(
            label = label,
            focused = focused,
            isError = errorMessage != null,
            trailing = icon?.let {
                {
                    MueIcon(
                        iconName = it,
                        tint = MueTheme.colors.accent,
                        size = MetricIconSize,
                    )
                }
            },
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
            ) {
                NumberBox(first, boxWidth) { focused = it }
                Suffix(first.suffix)
                NumberBox(second, boxWidth) { focused = it }
                Suffix(second.suffix)
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

@Composable
private fun NumberBox(part: ClockPart, width: Dp, onFocusChange: (Boolean) -> Unit) {
    val colors = MueTheme.colors
    BasicTextField(
        value = part.value,
        onValueChange = part.onValueChange,
        modifier = Modifier
            .width(width)
            .heightIn(min = ClockBoxHeight)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .testTag(part.testTag)
            .semantics { contentDescription = part.contentDescription },
        singleLine = true,
        textStyle = MueTheme.typography.fieldValue.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (part.value.isEmpty()) {
                    MueText(
                        text = EMPTY_NUMBER_HINT,
                        style = MueTheme.typography.fieldValue,
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun Suffix(text: String) {
    MueText(
        text = text,
        style = MueTheme.typography.caption,
        color = MueTheme.colors.textTertiary,
        modifier = Modifier.padding(bottom = 2.dp),
        maxLines = 1,
    )
}

/** The small grey line a card opens with, e.g. `Equipment · optional` under its icon. */
@Composable
private fun LabelWithIcon(label: String, icon: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueIcon(icon, tint = MueTheme.colors.accent, size = MetricIconSize)
        MueText(label, MueTheme.typography.label, color = MueTheme.colors.textTertiary, maxLines = 1)
    }
}

/** `Not set` is last, as in the prototype, and is what an unstated place means. */
private val ENVIRONMENTS: List<ActivityEnvironment> = listOf(
    ActivityEnvironment.INDOOR,
    ActivityEnvironment.OUTDOOR,
    ActivityEnvironment.UNKNOWN,
)

/** The prototype's two-column grid of measurements. */
private const val COLUMNS = 2

// endregion
