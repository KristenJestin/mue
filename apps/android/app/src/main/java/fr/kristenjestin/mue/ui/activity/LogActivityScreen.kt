package fr.kristenjestin.mue.ui.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueEffortSlider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueNotesField
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/** Three tiles a row, as in the prototype; six presets fit without a hidden gesture (PRD 15). */
private const val PRESETS_PER_ROW = 3

private val BackIconSize: Dp = 18.dp

/**
 * Choosing a preset and filling a session (PRD FR-ACTIVITY-004 to 008), and the same form
 * again when a session is edited (PRD 7).
 *
 * [sessionId] is null while creating, which is what tells `Save activity` from `Save changes`
 * and what decides whether `Delete activity` appears at all. [onOpenStrengthSession] leaves the
 * draft untouched: PRD 9.1 makes the detailed editor another view of the very same one.
 */
@Composable
fun LogActivityScreen(
    sessionId: ActivityId?,
    onBack: () -> Unit,
    onOpenStrengthSession: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = logActivityViewModel()

    // Idempotent: returning from the strength editor recomposes this screen, and the draft the
    // two share must survive that (PRD 9.1).
    LaunchedEffect(sessionId) { viewModel.start(sessionId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberMueHaptics(state.hapticsEnabled)

    // PRD FR-ACTIVITY-010: a short vibration on the beat the button discharges.
    LaunchedEffect(state.justSaved) { if (state.justSaved) haptics.confirm() }

    val actions = remember(viewModel, onBack, onSaved, onDeleted, onOpenStrengthSession) {
        LogActivityActions(
            onBack = onBack,
            onSelectPreset = viewModel::onPresetSelected,
            onOpenDatePicker = viewModel::onOpenDatePicker,
            onDismissDatePicker = viewModel::onDismissDatePicker,
            onDateSelected = viewModel::onDateSelected,
            onOpenTimePicker = viewModel::onOpenTimePicker,
            onDismissTimePicker = viewModel::onDismissTimePicker,
            onStartTimeSelected = viewModel::onStartTimeSelected,
            onHoursChange = viewModel::onHoursChange,
            onMinutesChange = viewModel::onMinutesChange,
            onEffortChange = viewModel::onEffortChange,
            onNotesChange = viewModel::onNotesChange,
            onMetricChange = viewModel::onMetricChange,
            onPaceChange = viewModel::onPaceChange,
            onOpenMovementPicker = viewModel::onOpenMovementPicker,
            onOpenEquipmentPicker = viewModel::onOpenEquipmentPicker,
            onPickerQueryChange = viewModel::onPickerQueryChange,
            onPickerSelect = viewModel::onCatalogEntrySelected,
            onPickerCreate = viewModel::onCreateFromSearch,
            onPickerDismiss = viewModel::onDismissPicker,
            onEnvironmentSelected = viewModel::onEnvironmentSelected,
            onEquipmentRemoved = viewModel::onEquipmentRemoved,
            onQuickLog = viewModel::onQuickLogSelected,
            onDetailedLog = {
                viewModel.onDetailedLogSelected()
                onOpenStrengthSession()
            },
            onConfirmQuickLog = viewModel::onConfirmQuickLog,
            onCancelQuickLog = viewModel::onCancelQuickLog,
            onSave = viewModel::save,
            onSaved = {
                viewModel.onSaveConfirmationFinished()
                onSaved()
            },
            onRequestDelete = viewModel::onRequestDelete,
            onCancelDelete = viewModel::onCancelDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onDeleted = {
                viewModel.onDeleteConfirmationFinished()
                onDeleted()
            },
        )
    }

    LogActivityContent(state = state, actions = actions, modifier = modifier)
}

/** Everything the form can ask for, so the layout can be driven without a database behind it. */
@Stable
internal class LogActivityActions(
    val onBack: () -> Unit = {},
    val onSelectPreset: (ActivityPreset) -> Unit = {},
    val onOpenDatePicker: () -> Unit = {},
    val onDismissDatePicker: () -> Unit = {},
    val onDateSelected: (LocalDate) -> Unit = {},
    val onOpenTimePicker: () -> Unit = {},
    val onDismissTimePicker: () -> Unit = {},
    /** Null is `Clear`: PRD 8.2 keeps the start time optional and never makes it midnight. */
    val onStartTimeSelected: (LocalTime?) -> Unit = {},
    val onHoursChange: (String) -> Unit = {},
    val onMinutesChange: (String) -> Unit = {},
    val onEffortChange: (Int) -> Unit = {},
    val onNotesChange: (String) -> Unit = {},
    val onMetricChange: (MetricKind, String) -> Unit = { _, _ -> },
    /** Null keeps the box that was not touched; the pair is stored joined as `m:ss`. */
    val onPaceChange: (String?, String?) -> Unit = { _, _ -> },
    val onOpenMovementPicker: () -> Unit = {},
    val onOpenEquipmentPicker: () -> Unit = {},
    val onPickerQueryChange: (String) -> Unit = {},
    val onPickerSelect: (String) -> Unit = {},
    val onPickerCreate: () -> Unit = {},
    val onPickerDismiss: () -> Unit = {},
    val onEnvironmentSelected: (ActivityEnvironment) -> Unit = {},
    val onEquipmentRemoved: (Int) -> Unit = {},
    val onQuickLog: () -> Unit = {},
    val onDetailedLog: () -> Unit = {},
    val onConfirmQuickLog: () -> Unit = {},
    val onCancelQuickLog: () -> Unit = {},
    val onSave: () -> Unit = {},
    /** Fired once the button's discharge has played out (contract decision 8). */
    val onSaved: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDeleted: () -> Unit = {},
)

@Composable
internal fun LogActivityContent(
    state: LogActivityUiState,
    actions: LogActivityActions,
    modifier: Modifier = Modifier,
) {
    MueSubScreenScaffold(
        title = state.screenTitle,
        onNavigateBack = actions.onBack,
        navigationIcon = {
            MueIcon(
                iconName = MueIcons.ARROW_LEFT,
                tint = MueTheme.colors.textSecondary,
                size = BackIconSize,
            )
        },
        modifier = modifier,
        horizontalPadding = MueTheme.spacing.screenHorizontal,
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            var actionHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Outside the scroll, so the viewport itself ends above the pinned action:
                    // a field that takes focus is then brought into a place the keyboard and
                    // the action leave visible, rather than under them.
                    .padding(bottom = actionHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
            ) {
                MueScreenTitle(
                    title = LogActivityMessages.TITLE,
                    eyebrow = LogActivityMessages.EYEBROW,
                    modifier = Modifier.padding(horizontal = MueTheme.spacing.sm),
                )

                PresetTiles(state, actions)
                CommonFields(state, actions)

                // Resolved out here because `transitionSpec` runs outside composition.
                val transition = presetTransition()
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { transition },
                    // The whole state travels, keyed on the preset alone: typing updates the
                    // block in place, and only a change of preset starts a transition — with
                    // the outgoing block still drawing the fields it was configured with.
                    contentKey = { it.preset },
                    label = "presetForm",
                ) { presetState ->
                    ActivityPresetForm(presetState, actions)
                }

                MueEffortSlider(
                    value = state.perceivedEffort,
                    onValueChange = actions.onEffortChange,
                    modifier = Modifier.testTag(ActivityTestTags.EFFORT_SLIDER),
                    icon = { MueIcon(ActivityIcons.GAUGE, tint = MueTheme.colors.accent) },
                )

                MueNotesField(
                    value = state.notes,
                    onValueChange = actions.onNotesChange,
                    modifier = Modifier.testTag(ActivityTestTags.NOTES_FIELD),
                    label = LogActivityMessages.NOTES_LABEL,
                    icon = {
                        MueIcon(ActivityIcons.NOTEBOOK_PEN, tint = MueTheme.colors.accent)
                    },
                )
            }

            MueStickyBottomAction(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        actionHeight = with(density) { size.height.toDp() }
                    },
                // The scaffold already holds the screen gutter; adding it again would inset
                // the save action from the very fields it belongs to.
                horizontalPadding = 0.dp,
            ) {
                SaveArea(state, actions)
            }
        }
    }

    ActivityDateSheet(
        visible = state.datePickerVisible,
        selected = state.date,
        today = state.today,
        onDismiss = actions.onDismissDatePicker,
        onConfirm = actions.onDateSelected,
    )

    ActivityTimeSheet(
        visible = state.timePickerVisible,
        selected = state.startTime,
        onDismiss = actions.onDismissTimePicker,
        onConfirm = actions.onStartTimeSelected,
    )

    CatalogPickerSheet(
        picker = state.picker,
        onQueryChange = actions.onPickerQueryChange,
        onSelect = actions.onPickerSelect,
        onCreate = actions.onPickerCreate,
        onDismiss = actions.onPickerDismiss,
    )

    ActivityConfirmations(state, actions)
}

// region Sections

@Composable
private fun PresetTiles(state: LogActivityUiState, actions: LogActivityActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ActivityTestTags.PRESET_ROW),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        ActivityPreset.entries.chunked(PRESETS_PER_ROW).forEach { row ->
            MueChoiceRow {
                row.forEach { preset ->
                    MueChoiceCard(
                        label = preset.label,
                        selected = preset == state.preset,
                        onClick = { actions.onSelectPreset(preset) },
                        icon = {
                            MueIcon(
                                iconName = ActivityIcons.forPreset(preset),
                                tint = if (preset == state.preset) {
                                    MueTheme.colors.onAccentSoft
                                } else {
                                    MueTheme.colors.textTertiary
                                },
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(ActivityTestTags.preset(preset.id)),
                    )
                }
            }
        }
    }
}

/** Date, optional start time and the duration every session must have (PRD FR-ACTIVITY-005). */
@Composable
private fun CommonFields(state: LogActivityUiState, actions: LogActivityActions) {
    val locale = rememberMueLocale()
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        /*
         * Date and start time are one row and have to read as one control, so the pair is
         * measured together: `IntrinsicSize.Min` gives the row the taller of the two boxes and
         * `fillMaxHeight` makes both exactly that, whatever a long date or a short clock would
         * have asked for on its own. Each box sizing to its own content is what left the two
         * ending at different heights.
         */
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            MuePickerField(
                label = LogActivityMessages.DATE_LABEL,
                value = LogActivityFormat.date(state.date, locale),
                onClick = actions.onOpenDatePicker,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(ActivityTestTags.DATE_FIELD),
                onClickLabel = LogActivityMessages.CHANGE_DATE,
            )

            // The same component as the date beside it, not a pair of number boxes: both open a
            // panel, so the two read and measure alike. PRD 14.1's table asks for no icon here.
            MuePickerField(
                label = LogActivityMessages.START_TIME_LABEL,
                value = LogActivityFormat.startTime(state.startTime, locale),
                onClick = actions.onOpenTimePicker,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(ActivityTestTags.START_TIME_FIELD),
                onClickLabel = LogActivityMessages.CHANGE_START_TIME,
            )
        }

        // Under the row rather than inside either column, so a message on one side cannot make
        // that side's box taller than the other's.
        if (state.dateError != null || state.startTimeError != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
                ScheduleError(state.dateError, Modifier.weight(1f))
                ScheduleError(state.startTimeError, Modifier.weight(1f))
            }
        }

        TwoPartNumberField(
            label = LogActivityMessages.DURATION_LABEL,
            icon = ActivityIcons.TIMER,
            first = ClockPart(
                value = state.hours,
                onValueChange = actions.onHoursChange,
                suffix = LogActivityMessages.HOURS_SUFFIX,
                contentDescription = "Duration hours",
                testTag = ActivityTestTags.DURATION_HOURS_FIELD,
            ),
            second = ClockPart(
                value = state.minutes,
                onValueChange = actions.onMinutesChange,
                suffix = LogActivityMessages.MINUTES_SUFFIX,
                contentDescription = "Duration minutes",
                testTag = ActivityTestTags.DURATION_MINUTES_FIELD,
            ),
            errorMessage = state.durationError,
        )
    }
}

@Composable
private fun ColumnScope.SaveArea(state: LogActivityUiState, actions: LogActivityActions) {
    // PRD 12: the message beside the action, so a screen reader hears why the save did nothing.
    state.formError?.let { message ->
        MueText(
            text = message,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.error,
            modifier = Modifier.semantics {
                error(message)
                liveRegion = LiveRegionMode.Polite
            },
        )
    }

    state.saveError?.let { message ->
        MueText(
            text = message,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (state.justDeleted) {
        val beat = MueMotion.durationOf(MueMotion.SaveConfirmationMillis)
        // The same rule as the save: the confirmation is read, then the screen leaves.
        LaunchedEffect(Unit) {
            delay(beat.toLong())
            actions.onDeleted()
        }
        MueText(
            text = LogActivityMessages.ACTIVITY_DELETED,
            style = MueTheme.typography.bodyStrong,
            color = MueTheme.colors.accent,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    MuePrimaryButton(
        label = state.saveLabel,
        onClick = actions.onSave,
        modifier = Modifier.testTag(ActivityTestTags.SAVE_BUTTON),
        enabled = !state.justDeleted,
        success = state.justSaved,
        onSuccessFinished = actions.onSaved,
    )

    if (state.saveError != null) {
        MueSecondaryButton(label = LogActivityMessages.TRY_AGAIN, onClick = actions.onSave)
    }

    if (state.isEditing) {
        MueSecondaryButton(
            label = LogActivityMessages.DELETE_ACTIVITY,
            onClick = actions.onRequestDelete,
            modifier = Modifier.testTag(ActivityTestTags.DELETE_BUTTON),
            enabled = !state.justDeleted,
            contentColor = MueTheme.colors.error,
        )
    }
}

/** One half of the schedule row's message line; the empty half keeps the columns aligned. */
@Composable
private fun ScheduleError(message: String?, modifier: Modifier) {
    if (message == null) {
        Spacer(modifier)
        return
    }
    MueText(
        text = message,
        style = MueTheme.typography.caption,
        color = MueTheme.colors.error,
        modifier = modifier
            .padding(horizontal = MueTheme.spacing.xs)
            .semantics { error(message) },
    )
}

// endregion

/**
 * PRD 14.2: the old block leaves in a short fade, the new one arrives with a slight vertical
 * move. Reduced motion keeps the change and drops the travel.
 */
@Composable
@ReadOnlyComposable
private fun presetTransition(): ContentTransform {
    val enter = MueMotion.spec<Float>(MueMotion.PresetChangeMillis, MueMotion.Enter)
    val exit = MueMotion.spec<Float>(MueMotion.PresetChangeMillis, MueMotion.Exit)
    if (LocalReduceMotion.current) return fadeIn(enter) togetherWith fadeOut(exit)
    val offset = MueMotion.spec<IntOffset>(MueMotion.PresetChangeMillis, MueMotion.Standard)
    return (
        slideInVertically(offset) { height -> height / 12 } + fadeIn(enter)
        ) togetherWith fadeOut(exit)
}

@Preview(name = "Log activity", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun LogActivityPreview() {
    MueTheme {
        LogActivityContent(
            state = LogActivityUiState(
                hours = "0",
                minutes = "45",
                perceivedEffort = 6,
                metrics = ActivityPreset.TREADMILL_WALK.metrics.map { kind ->
                    MetricFieldState(
                        kind = kind,
                        input = "",
                        source = ActivityPreset.TREADMILL_WALK.sourceOf(kind),
                    )
                },
            ),
            actions = LogActivityActions(),
        )
    }
}
