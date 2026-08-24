package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.SetMeasure
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueEffortSlider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueNotesField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSetField
import fr.kristenjestin.mue.ui.components.MueSetHeaderRow
import fr.kristenjestin.mue.ui.components.MueSetListActions
import fr.kristenjestin.mue.ui.components.MueSetMeasure
import fr.kristenjestin.mue.ui.components.MueSetRow
import fr.kristenjestin.mue.ui.components.MueSetRowAction
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal const val STRENGTH_SCREEN_TITLE = "Strength session"
internal const val STRENGTH_TITLE = "Strong, your way."
internal const val EXERCISES_TITLE = "Exercises"
internal const val ADD_EXERCISE_LABEL = "Add exercise"
internal const val ADD_ANOTHER_EXERCISE_LABEL = "Add another exercise"
internal const val NO_EXERCISE_MESSAGE = "Add a first exercise and its sets will follow."
internal const val NEEDS_A_VALID_SET = "Add one complete set to save this session."
internal const val SAVE_NEW_SESSION = "Save activity"
internal const val SAVE_EXISTING_SESSION = "Save changes"
internal const val EXERCISE_NOTE_LABEL = "Exercise note"
internal const val ADD_NOTE_LABEL = "Add a note"
internal const val TRACKING_MODE_SHEET_TITLE = "How is each set tracked?"

/** PRD 12: a missing optional value is drawn as an absence, never as a zero. */
private const val EMPTY_CELL = "—"

/** Room under the list for the pinned save action, which floats over it. */
private val BottomActionClearance: Dp = 128.dp

/** The prototype's `h-10 w-10` exercise avatar. */
private val ExerciseAvatarSize: Dp = 40.dp

/**
 * The detailed strength editor: exercises, sets and their reps, loads and durations
 * (PRD FR-ACTIVITY-009).
 *
 * It edits the draft the log form already holds, so [onBack] returns to that form with
 * everything typed here still in place (PRD 9.1). [state] has no default on purpose: the one
 * implementation that writes is `rememberSharedStrengthSessionState`, and a screen that silently
 * kept a draft of its own would look identical and save nothing.
 */
@Composable
fun StrengthSessionScreen(
    state: StrengthSessionState,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StrengthSessionScreen(
        draft = state.draft,
        catalogue = state.catalogue,
        lastPerformances = state.lastPerformances,
        saved = state.saved,
        onEdit = state::edit,
        onSave = state::save,
        onBack = onBack,
        onSaved = onSaved,
        modifier = modifier,
    )
}

/**
 * Everything the editor needs from whoever owns the draft.
 *
 * PRD 9.1 makes `Quick log` and `Detailed log` two views of one session, so the draft cannot
 * belong to this screen: it belongs to the log form's `LogActivityViewModel`, which implements
 * this interface in one adapter and answers [edit] with the single line
 * `updateDraft { StrengthDraftEditor.apply(it, edit) }`.
 *
 * The screen never learns which implementation it is talking to, which is also what lets the
 * Compose tests drive it with a list and a lambda.
 */
@Stable
interface StrengthSessionState {

    val draft: ActivityDraft

    /** PRD FR-ACTIVITY-009: most recently used first, then the rest of the catalogue. */
    val catalogue: List<ExerciseDefinition>

    /** PRD 11.4, keyed by `ExerciseDefinitionId.value`; absent means never practised. */
    val lastPerformances: Map<String, LastPerformance>

    /** True once the write has landed; the save button plays its discharge on it. */
    val saved: Boolean

    fun edit(edit: StrengthEdit)

    fun save()
}

/**
 * The screen proper, with its state supplied.
 *
 * Stateless on purpose: every mutation leaves through [onEdit] as a [StrengthEdit], so the
 * rules it stands for are unit-tested in `StrengthDraftEditor` rather than through a device.
 */
@Composable
internal fun StrengthSessionScreen(
    draft: ActivityDraft,
    catalogue: List<ExerciseDefinition>,
    lastPerformances: Map<String, LastPerformance>,
    saved: Boolean,
    onEdit: (StrengthEdit) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    locale: Locale = Locale.getDefault(),
) {
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    var modeSheetFor by rememberSaveable { mutableStateOf(NO_EXERCISE) }
    var announcement by remember { mutableStateOf<String?>(null) }

    // The duplicated row lands at the end of its exercise, and PRD 14.2 gives it one amber
    // beat. Held here rather than in the draft: it is a thing that just happened, not a value.
    var duplicated by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val duration = ActivityValidation.validateDuration(draft.hours, draft.minutes)
    val energyInput = draft.presetDraft().metricInput(MetricKind.ESTIMATED_ENERGY)
    val energy = ActivityValidation.validateMetric(MetricKind.ESTIMATED_ENERGY, energyInput)
    // Counting walks every set of every exercise, so it is tied to the list rather than to the
    // frame: typing in the duration box must not re-parse the whole session.
    val setCount = remember(draft.exercises) { StrengthDraftEditor.validSetCount(draft) }
    val hasValidSet = setCount > 0
    val announce: (String) -> Unit = { announcement = it }

    Box(modifier = modifier.fillMaxSize()) {
        MueSubScreenScaffold(
            title = STRENGTH_SCREEN_TITLE,
            onNavigateBack = onBack,
            navigationIcon = {
                MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
            },
            navigationContentDescription = "Back to the activity form",
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(ActivityTestTags.EXERCISE_LIST),
                contentPadding = PaddingValues(
                    top = MueTheme.spacing.md,
                    bottom = BottomActionClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                item(key = "title") {
                    MueScreenTitle(
                        title = STRENGTH_TITLE,
                        eyebrow = longDate(today, locale),
                        modifier = Modifier.padding(bottom = MueTheme.spacing.sm),
                    )
                }

                item(key = "session") {
                    SessionFields(
                        draft = draft,
                        durationError = duration.errorMessage,
                        energyInput = energyInput,
                        energyError = energy.errorMessage,
                        onEdit = onEdit,
                    )
                }

                item(key = "exercisesHeader") {
                    ExercisesHeader(
                        exerciseCount = draft.exercises.size,
                        setCount = setCount,
                        onAddExercise = { pickerVisible = true },
                        modifier = Modifier.padding(top = MueTheme.spacing.sm),
                    )
                }

                if (draft.exercises.isEmpty()) {
                    item(key = "empty") {
                        MueText(
                            text = NO_EXERCISE_MESSAGE,
                            style = MueTheme.typography.body,
                            color = MueTheme.colors.textTertiary,
                        )
                    }
                }

                itemsIndexed(
                    items = draft.exercises,
                    key = { index, exercise -> "${exercise.definitionId}#$index" },
                ) { index, exercise ->
                    ExerciseCard(
                        index = index,
                        exercise = exercise,
                        count = draft.exercises.size,
                        lastPerformance = lastPerformances[exercise.definitionId],
                        // PRD 9.2 stores the mode on the definition, so a change only reaches
                        // the database while that definition is still new. See the report.
                        modeEditable = catalogue.none { it.id.value == exercise.definitionId },
                        duplicatedSet = duplicated?.takeIf { it.first == index }?.second,
                        locale = locale,
                        onEdit = { edit ->
                            duplicated = (edit as? StrengthEdit.DuplicateLastSet)
                                ?.let { it.exercise to exercise.sets.size }
                            onEdit(edit)
                        },
                        onAnnounce = announce,
                        onOpenModeSheet = { modeSheetFor = index },
                    )
                }

                // The prototype's second entry point: after filling an exercise, the next one
                // is where the eye already is rather than back at the section header.
                if (draft.exercises.isNotEmpty()) {
                    item(key = "addAnother") {
                        MueDashedAction(
                            label = ADD_ANOTHER_EXERCISE_LABEL,
                            onClick = { pickerVisible = true },
                            icon = {
                                MueIcon(
                                    ActivityIcons.PLUS_CIRCLE,
                                    tint = MueTheme.contentColor,
                                    size = 16.dp,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        MueStickyBottomAction(modifier = Modifier.align(Alignment.BottomCenter)) {
            if (!hasValidSet) {
                MueText(
                    text = NEEDS_A_VALID_SET,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            MuePrimaryButton(
                label = if (draft.editingSessionId == null) {
                    SAVE_NEW_SESSION
                } else {
                    SAVE_EXISTING_SESSION
                },
                onClick = onSave,
                enabled = hasValidSet && duration.errorMessage == null &&
                    energy.errorMessage == null,
                success = saved,
                // Contract decision 8: the discharge plays, and the return follows it.
                onSuccessFinished = onSaved,
                modifier = Modifier.testTag(ActivityTestTags.SAVE_BUTTON),
            )
        }

        announcement?.let { message ->
            // Invisible, but a real node: TalkBack reads a polite live region when its text
            // changes, which is how PRD 15 wants a move or a removal reported.
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = message
                    },
            )
        }
    }

    ExercisePickerSheet(
        visible = pickerVisible,
        catalogue = catalogue,
        onDismissRequest = { pickerVisible = false },
        onPick = { definition ->
            pickerVisible = false
            onEdit(StrengthEdit.AddExercise(definition))
            announce("${definition.name} added")
        },
        onCreate = { name, mode ->
            val definition = StrengthDraftEditor.definitionFor(name, mode, catalogue, draft)
            if (definition != null) {
                pickerVisible = false
                onEdit(StrengthEdit.AddExercise(definition))
                announce("${definition.name} added")
            }
        },
    )

    val modeExercise = draft.exercises.getOrNull(modeSheetFor)
    TrackingModeSheet(
        visible = modeExercise != null,
        selected = modeExercise?.let { TrackingMode.fromId(it.trackingModeId) },
        onDismissRequest = { modeSheetFor = NO_EXERCISE },
        onSelect = { mode ->
            onEdit(StrengthEdit.SetTrackingMode(modeSheetFor, mode))
            modeSheetFor = NO_EXERCISE
        },
    )
}

/** No exercise has its tracking-mode sheet open; a nullable index would not survive a Bundle. */
private const val NO_EXERCISE = -1

/**
 * Duration, estimated energy and perceived effort — the same three draft fields the log form
 * writes (PRD 9.1), so a value typed on either screen is the value on the other.
 *
 * The duration is hours and minutes on both forms, where the prototype of this screen shows a
 * bare minutes box: PRD FR-ACTIVITY-005 governs, per the build contract.
 */
@Composable
private fun SessionFields(
    draft: ActivityDraft,
    durationError: String?,
    energyInput: String,
    energyError: String?,
    onEdit: (StrengthEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm)) {
            MueTextField(
                label = "Hours",
                value = draft.hours,
                onValueChange = { onEdit(StrengthEdit.SetDurationHours(it)) },
                placeholder = "0",
                suffix = "h",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MueTheme.typography.metricMedium,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivityTestTags.DURATION_HOURS_FIELD),
            )
            MueTextField(
                label = "Minutes",
                value = draft.minutes,
                onValueChange = { onEdit(StrengthEdit.SetDurationMinutes(it)) },
                placeholder = "0",
                suffix = "min",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MueTheme.typography.metricMedium,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivityTestTags.DURATION_MINUTES_FIELD),
            )
        }
        durationError?.let { FieldError(it) }

        MueTextField(
            label = "Estimated energy · optional",
            value = energyInput,
            onValueChange = { onEdit(StrengthEdit.SetEstimatedEnergy(it)) },
            placeholder = EMPTY_CELL,
            suffix = "kcal",
            errorMessage = energyError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MueTheme.typography.metricMedium,
            modifier = Modifier.testTag(
                ActivityTestTags.metricField(MetricKind.ESTIMATED_ENERGY.id),
            ),
        )

        // PRD 9.4: the session's own effort stays offered in every tracking mode, unlike the
        // per-set column, which only exists where the row has one free.
        MueEffortSlider(
            value = draft.perceivedEffort,
            onValueChange = { onEdit(StrengthEdit.SetSessionEffort(it)) },
            label = "Perceived effort · optional",
            icon = { MueIcon(ActivityIcons.GAUGE, tint = MueTheme.colors.textTertiary, size = 14.dp) },
            modifier = Modifier.testTag(ActivityTestTags.EFFORT_SLIDER),
        )
    }
}

@Composable
private fun FieldError(message: String) {
    MueText(
        text = message,
        style = MueTheme.typography.caption,
        color = MueTheme.colors.error,
        modifier = Modifier
            .padding(horizontal = MueTheme.spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * `Exercises`, what the session currently amounts to (PRD 11.2), and the action that adds one.
 *
 * The action lives here rather than only under the list so that it stays one tap away however
 * far the list has grown — a session of six exercises is a long way to scroll for a seventh.
 */
@Composable
private fun ExercisesHeader(
    exerciseCount: Int,
    setCount: Int,
    onAddExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MueText(
                text = EXERCISES_TITLE,
                style = MueTheme.typography.sectionTitle,
                modifier = Modifier.semantics { heading() },
            )
            MueText(
                text = "${plural(exerciseCount, "exercise", "exercises")} · " +
                    plural(setCount, "set", "sets"),
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textQuiet,
                maxLines = 1,
            )
        }
        MueDashedAction(
            label = ADD_EXERCISE_LABEL,
            onClick = onAddExercise,
            icon = { MueIcon(ActivityIcons.PLUS, tint = MueTheme.contentColor, size = 14.dp) },
            modifier = Modifier.testTag(ActivityTestTags.ADD_EXERCISE),
        )
    }
}

private fun plural(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

/**
 * One exercise: what it is, what it came to last time, how it is tracked, and its sets.
 *
 * The header carries the removal alone. Moving and the tracking mode sit on a second row,
 * because three 48 dp targets beside a name leave that name 98 dp on a 390 dp screen — and
 * PRD 15 will not have the targets any smaller.
 */
@Composable
private fun ExerciseCard(
    index: Int,
    exercise: ExerciseDraft,
    count: Int,
    lastPerformance: LastPerformance?,
    modeEditable: Boolean,
    duplicatedSet: Int?,
    locale: Locale,
    onEdit: (StrengthEdit) -> Unit,
    onAnnounce: (String) -> Unit,
    onOpenModeSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val mode = TrackingMode.fromId(exercise.trackingModeId)
    val measures = remember(mode) { measuresOf(mode) }
    val position = index + 1

    MueSurfaceCard(
        modifier = modifier.testTag(ActivityTestTags.exercise(index)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MueTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(ExerciseAvatarSize)
                    .clip(MueTheme.shapes.small)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(ActivityIcons.DUMBBELL, tint = colors.accent, size = 16.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                MueText(exercise.name, MueTheme.typography.bodyStrong, maxLines = 1)
                LastPerformanceFormat.format(lastPerformance, locale)?.let { line ->
                    MueText(line, MueTheme.typography.micro, color = colors.textQuiet, maxLines = 1)
                }
            }
            MueSetRowAction(
                contentDescription = "Remove ${exercise.name}",
                onClick = {
                    onEdit(StrengthEdit.RemoveExercise(index))
                    onAnnounce("${exercise.name} removed")
                },
                icon = { MueIcon(MueIcons.CLOSE, tint = colors.textTertiary, size = 14.dp) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MueTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            // The pill keeps only the room its label needs, but the region it sits in takes the
            // rest: the two moves stay at the right edge, where every trailing action of this
            // card already is, and a long mode label is capped rather than pushing them off.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TrackingModePill(
                    mode = mode,
                    editable = modeEditable,
                    onClick = onOpenModeSheet,
                )
            }
            val canMoveUp = index > 0
            val canMoveDown = index < count - 1
            MueSetRowAction(
                contentDescription = "Move ${exercise.name} up",
                onClick = {
                    if (!canMoveUp) return@MueSetRowAction
                    onEdit(StrengthEdit.MoveExerciseUp(index))
                    onAnnounce("${exercise.name} moved to position $index of $count")
                },
                icon = {
                    MueIcon(
                        MueIcons.CHEVRON_UP,
                        tint = if (canMoveUp) colors.textSecondary else colors.textQuiet,
                        size = 16.dp,
                    )
                },
                modifier = Modifier.disabledUnless(canMoveUp),
            )
            MueSetRowAction(
                contentDescription = "Move ${exercise.name} down",
                onClick = {
                    if (!canMoveDown) return@MueSetRowAction
                    onEdit(StrengthEdit.MoveExerciseDown(index))
                    onAnnounce("${exercise.name} moved to position ${position + 1} of $count")
                },
                icon = {
                    MueIcon(
                        MueIcons.CHEVRON_DOWN,
                        tint = if (canMoveDown) colors.textSecondary else colors.textQuiet,
                        size = 16.dp,
                    )
                },
                modifier = Modifier.disabledUnless(canMoveDown),
            )
        }

        MueDivider()

        MueSetHeaderRow(measures)
        exercise.sets.forEachIndexed { setIndex, set ->
            MueSetRow(
                number = setIndex + 1,
                fields = measures.map { measure ->
                    MueSetField(
                        measure = measure,
                        value = set.valueOf(measure),
                        onValueChange = { raw ->
                            onEdit(
                                StrengthEdit.EditSet(index, setIndex, measure.toField(), raw),
                            )
                        },
                        placeholder = EMPTY_CELL,
                    )
                },
                onDelete = {
                    onEdit(StrengthEdit.RemoveSet(index, setIndex))
                    onAnnounce("Set ${setIndex + 1} of ${exercise.name} removed")
                },
                deleteIcon = { MueIcon(MueIcons.TRASH, tint = colors.textQuiet, size = 14.dp) },
                emphasised = setIndex == exercise.sets.lastIndex,
                justDuplicated = duplicatedSet == setIndex,
                deleteContentDescription = "Remove set ${setIndex + 1} of ${exercise.name}",
                modifier = Modifier.testTag(ActivityTestTags.set(index, setIndex)),
            )
        }

        MueSetListActions(
            onAddSet = {
                onEdit(StrengthEdit.AddSet(index))
                onAnnounce("Set ${exercise.sets.size + 1} added to ${exercise.name}")
            },
            onDuplicateLastSet = exercise.sets.lastOrNull()?.let {
                {
                    onEdit(StrengthEdit.DuplicateLastSet(index))
                    onAnnounce("Last set of ${exercise.name} duplicated")
                }
            },
            addIcon = { MueIcon(ActivityIcons.PLUS, tint = MueTheme.contentColor, size = 14.dp) },
            duplicateIcon = {
                MueIcon(ActivityIcons.COPY_PLUS, tint = MueTheme.contentColor, size = 14.dp)
            },
            modifier = Modifier.testTag(ActivityTestTags.addSet(index)),
        )

        ExerciseNote(
            notes = exercise.notes,
            name = exercise.name,
            onChange = { onEdit(StrengthEdit.SetExerciseNotes(index, it)) },
        )
    }
}

/** PRD 9.3: a comment on one exercise, and nothing on screen until it is wanted. */
@Composable
private fun ExerciseNote(
    notes: String,
    name: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(name) { mutableStateOf(false) }
    if (notes.isEmpty() && !open) {
        MueDashedAction(
            label = ADD_NOTE_LABEL,
            onClick = { open = true },
            icon = {
                MueIcon(ActivityIcons.NOTEBOOK_PEN, tint = MueTheme.contentColor, size = 14.dp)
            },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        MueNotesField(
            value = notes,
            onValueChange = onChange,
            label = EXERCISE_NOTE_LABEL,
            placeholder = "Anything worth remembering about $name?",
            maxLength = StrengthDraftEditor.MAX_EXERCISE_NOTES_LENGTH,
            minLines = 2,
            modifier = modifier,
        )
    }
}

/**
 * What the columns of this exercise mean, and — while the definition is still new — a way to
 * change it (PRD 9.2).
 */
@Composable
private fun TrackingModePill(
    mode: TrackingMode,
    editable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.pill
    Row(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(colors.surfaceStrong)
            .then(
                if (editable) {
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .semantics { contentDescription = "Tracking mode, ${mode.label}" }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = MueTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueText(
            text = mode.label,
            style = MueTheme.typography.chip,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (editable) {
            MueIcon(MueIcons.CHEVRON_RIGHT, tint = colors.textQuiet, size = 14.dp)
        }
    }
}

/** The four modes of PRD 9.2, two by two so `Weight & duration` has room to be read. */
@Composable
private fun TrackingModeSheet(
    visible: Boolean,
    selected: TrackingMode?,
    onDismissRequest: () -> Unit,
    onSelect: (TrackingMode) -> Unit,
) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = TRACKING_MODE_SHEET_TITLE,
        scrimContentDescription = "Close the tracking mode picker",
    ) {
        MueText(
            text = "Changing it clears whatever the new mode does not record.",
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textTertiary,
            modifier = Modifier.padding(bottom = MueTheme.spacing.md),
        )
        TrackingMode.entries.chunked(2).forEach { pair ->
            MueChoiceRow(modifier = Modifier.padding(bottom = MueTheme.spacing.sm)) {
                pair.forEach { mode ->
                    MueChoiceCard(
                        label = mode.label,
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        minHeight = MueMinTouchTarget,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// region Cell plumbing

private fun MueSetMeasure.toField(): StrengthSetField = when (this) {
    MueSetMeasure.LOAD -> StrengthSetField.LOAD
    MueSetMeasure.REPETITIONS -> StrengthSetField.REPETITIONS
    MueSetMeasure.DURATION -> StrengthSetField.DURATION
    MueSetMeasure.EFFORT -> StrengthSetField.EFFORT
}

private fun SetDraft.valueOf(measure: MueSetMeasure): String = when (measure) {
    MueSetMeasure.LOAD -> loadKg
    MueSetMeasure.REPETITIONS -> reps
    MueSetMeasure.DURATION -> durationSeconds
    // PRD 12 again: a set with no effort shows an empty cell, not a zero.
    MueSetMeasure.EFFORT -> perceivedEffort?.toString().orEmpty()
}

/**
 * The columns a mode offers, restated from the component's own table because that one is
 * internal to `ui.components`. Contract decision 3 lives there, not here.
 */
private fun measuresOf(mode: TrackingMode): List<MueSetMeasure> = buildList {
    if (mode.usesLoad) add(MueSetMeasure.LOAD)
    add(
        when (mode.primary) {
            SetMeasure.REPETITIONS -> MueSetMeasure.REPETITIONS
            SetMeasure.DURATION -> MueSetMeasure.DURATION
        },
    )
    if (mode.showsSetEffort) add(MueSetMeasure.EFFORT)
}

/**
 * A move that cannot happen keeps its 48 dp target — the row must not reflow as exercises are
 * reordered — but says so to the accessibility services rather than only going pale (PRD 15).
 */
private fun Modifier.disabledUnless(enabled: Boolean): Modifier =
    if (enabled) this else this.semantics { disabled() }

private fun longDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

// endregion

@Preview(name = "Strength session", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390, heightDp = 900)
@Composable
private fun StrengthSessionScreenPreview() {
    MueTheme {
        StrengthSessionScreen(
            draft = PreviewDraft,
            catalogue = emptyList(),
            lastPerformances = emptyMap(),
            saved = false,
            onEdit = {},
            onSave = {},
            onBack = {},
            onSaved = {},
            today = LocalDate.of(2026, 8, 23),
            locale = Locale.ENGLISH,
        )
    }
}

private val PreviewDraft = ActivityDraft(
    presetId = ActivityPreset.STRENGTH_TRAINING.id,
    hours = "1",
    minutes = "05",
    perceivedEffort = 7,
    detailed = true,
    exercises = listOf(
        ExerciseDraft(
            definitionId = "squat",
            name = "Barbell squat",
            trackingModeId = TrackingMode.WEIGHT_AND_REPS.id,
            sets = listOf(
                SetDraft(reps = "10", loadKg = "40"),
                SetDraft(reps = "8", loadKg = "60"),
                SetDraft(),
            ),
        ),
        ExerciseDraft(
            definitionId = "plank",
            name = "Plank",
            trackingModeId = TrackingMode.DURATION.id,
            sets = listOf(SetDraft(durationSeconds = "1:30", perceivedEffort = 6)),
        ),
    ),
)
