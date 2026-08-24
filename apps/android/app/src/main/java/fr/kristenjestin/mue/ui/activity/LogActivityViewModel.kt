package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.logic.StrengthRules
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.isValid
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySource
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.SetType
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Every transformation the detailed strength editor applies to the shared draft (PRD 9.1).
 *
 * The seam of the build contract's section 5: `Log activity` and `Strength session` edit the
 * *same* [ActivityDraft], so the editor is a set of pure `ActivityDraft -> ActivityDraft`
 * functions — `StrengthDraftEditor` — and this ViewModel is the only thing that owns state.
 * Nothing here parses: a set keeps the raw text it was typed with until the save path reads it.
 *
 * The duration, the effort and the estimated energy shown at the top of the strength screen are
 * *not* edits: they are the session's own fields, and the editor binds them straight to
 * [LogActivityViewModel.onHoursChange], [LogActivityViewModel.onEffortChange] and
 * [LogActivityViewModel.onMetricChange], so typing in either screen moves the same value.
 */
@Immutable
sealed interface StrengthEdit {

    /** PRD FR-ACTIVITY-009: adding an exercise seeds one empty set, never a plausible one. */
    data class AddExercise(
        val definitionId: String,
        val name: String,
        val trackingModeId: String,
        val equipmentId: String? = null,
        val isCustom: Boolean = false,
    ) : StrengthEdit

    data class RemoveExercise(val exerciseIndex: Int) : StrengthEdit

    /** [by] is `-1` for `Move up` and `+1` for `Move down` (contract decision 4). */
    data class MoveExercise(val exerciseIndex: Int, val by: Int) : StrengthEdit

    data class SetExerciseNotes(val exerciseIndex: Int, val notes: String) : StrengthEdit

    data class AddSet(val exerciseIndex: Int) : StrengthEdit

    data class DuplicateLastSet(val exerciseIndex: Int) : StrengthEdit

    data class RemoveSet(val exerciseIndex: Int, val setIndex: Int) : StrengthEdit

    data class SetReps(val exerciseIndex: Int, val setIndex: Int, val input: String) : StrengthEdit

    data class SetLoad(val exerciseIndex: Int, val setIndex: Int, val input: String) : StrengthEdit

    data class SetDuration(
        val exerciseIndex: Int,
        val setIndex: Int,
        val input: String,
    ) : StrengthEdit

    data class SetEffort(
        val exerciseIndex: Int,
        val setIndex: Int,
        val value: Int?,
    ) : StrengthEdit
}

/**
 * The one owner of the activity being written (PRD FR-ACTIVITY-004 to 011).
 *
 * It is scoped to the hosting activity under a fixed key rather than to a screen, because
 * `Log activity` and `Strength session` are two views of a single draft: PRD 9.1 makes the
 * Quick/Detailed choice reversible while the screen is open, so the draft has to outlive
 * navigating between them.
 *
 * The whole draft crosses [SavedStateHandle] as one JSON string. A per-preset map of unbounded
 * exercise lists cannot be flattened into Bundle keys, and keeping the raw text is what brings
 * a half-typed `7,` back after the process has been killed (PRD 16.4).
 */
class LogActivityViewModel(
    private val activities: ActivityRepository,
    private val catalog: ExerciseCatalogRepository,
    preferences: UserPreferencesRepository,
    private val savedState: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val _draft = MutableStateFlow(ActivityDraft.fromJson(savedState[KEY_DRAFT]))

    /** What the strength editor reads. Its own state is derived from this and nothing else. */
    val draft: StateFlow<ActivityDraft> = _draft.asStateFlow()

    /**
     * What a save attempt found and which panel is open — never the typed values.
     *
     * These deliberately stay out of [savedState]: a message is the result of pressing `Save`,
     * not something anyone typed, and a dialog reopening itself after a process death would be
     * noise. They survive a rotation because the ViewModel does.
     */
    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<LogActivityUiState> = combine(
        _draft,
        transient,
        preferences.preferences,
    ) { draft, flags, prefs ->
        build(draft, flags, prefs.hapticsEnabled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = build(_draft.value, transient.value, hapticsEnabled = true),
    )

    // --- Opening the form -------------------------------------------------------------

    /**
     * Called by the screen on every entry, and idempotent by design.
     *
     * Returning from the strength editor recomposes the form from scratch, so a naive reset
     * here would wipe the very draft the two screens share. The marker is what tells a genuine
     * new visit from a return: it is cleared once a save or a delete has been confirmed, which
     * is what makes the next `Log activity` open on a blank form.
     */
    fun start(sessionId: ActivityId?) {
        val marker = sessionId?.value.orEmpty()
        if (savedState.get<String>(KEY_STARTED_FOR) == marker) return
        savedState[KEY_STARTED_FOR] = marker
        transient.value = Transient(isLoading = sessionId != null)
        if (sessionId == null) {
            replaceDraft(ActivityDraft())
        } else {
            viewModelScope.launch { prefill(sessionId) }
        }
    }

    private suspend fun prefill(id: ActivityId) {
        val detail = runCatching { activities.findDetail(id) }.getOrNull()
        if (detail == null) {
            // The row is gone. Keeping the id means `Save` recreates it rather than silently
            // writing a second session under a new one.
            replaceDraft(ActivityDraft(editingSessionId = id.value))
            transient.update { it.copy(isLoading = false) }
            return
        }
        val session = detail.session
        val preset = ActivityPreset.of(session.movement, detail.equipment)
        replaceDraft(
            ActivityDraft(
                editingSessionId = id.value,
                presetId = preset.id,
                startedOn = session.startedOn.toString(),
                startedAtTime = session.startedAtTime?.format(LogActivityFormat.TIME),
                hours = session.duration.hoursPart.toString(),
                minutes = session.duration.minutesPart.toString(),
                perceivedEffort = session.perceivedEffort?.value,
                notes = session.notes.orEmpty(),
                detailed = detail.exercises.isNotEmpty(),
                byPreset = mapOf(preset.id to presetDraftOf(preset, detail)),
                exercises = detail.exercises.map(::exerciseDraftOf),
            ),
        )
        transient.update {
            it.copy(isLoading = false, storedExerciseCount = detail.exercises.size)
        }
    }

    private fun presetDraftOf(preset: ActivityPreset, detail: ActivitySessionDetail): PresetDraft =
        PresetDraft(
            metrics = detail.metrics.values.associate { metric ->
                metric.kind.id to LogActivityFormat.metricInput(metric.kind, metric.value, locale())
            },
            movementId = detail.session.movement.id,
            customMovementName = detail.session.customMovementName.orEmpty(),
            environmentId = detail.session.environment.id,
            // The preset's own machine is implicit in the tile, so it is not offered as a
            // removable tag; anything else the session carries is kept and written back.
            equipment = detail.equipment
                .filterNot { it.equipmentType == preset.equipment && it.customName == null }
                .map { EquipmentDraft(it.equipmentType.id, it.customName.orEmpty()) },
        )

    private fun exerciseDraftOf(detail: StrengthExerciseDetail): ExerciseDraft = ExerciseDraft(
        definitionId = detail.definition.id.value,
        name = detail.definition.name,
        trackingModeId = detail.definition.trackingMode.id,
        equipmentId = detail.definition.equipment?.id,
        isCustom = detail.definition.isCustom,
        notes = detail.exercise.notes.orEmpty(),
        sets = detail.sets.sortedBy { it.position }.map { set ->
            SetDraft(
                setTypeId = set.setType.id,
                reps = set.repetitions?.toString().orEmpty(),
                loadKg = set.load?.let { LogActivityFormat.loadInput(it, locale()) }.orEmpty(),
                durationSeconds = set.duration
                    ?.let { LogActivityFormat.clock(it.seconds, locale()) }
                    .orEmpty(),
                perceivedEffort = set.perceivedEffort?.value,
            )
        },
    )

    // --- The preset -------------------------------------------------------------------

    /**
     * PRD FR-ACTIVITY-004: nothing is asked and nothing is lost. The draft keeps a block per
     * preset visited, so the abandoned incline is still there on the way back and is simply
     * never read while another preset is on screen.
     */
    fun onPresetSelected(preset: ActivityPreset) {
        if (preset.id == _draft.value.presetId) return
        transient.update { it.copy(metricErrors = emptyMap(), movementError = null, formError = null) }
        updateDraft { it.copy(presetId = preset.id) }
    }

    // --- Common fields ----------------------------------------------------------------

    fun onOpenDatePicker() = transient.update { it.copy(datePickerVisible = true) }

    fun onDismissDatePicker() = transient.update { it.copy(datePickerVisible = false) }

    fun onDateSelected(date: LocalDate) {
        transient.update { it.copy(datePickerVisible = false, dateError = null, formError = null) }
        updateDraft { it.copy(startedOn = date.toString()) }
    }

    fun onStartHoursChange(raw: String) = onStartTimeChange(digits(raw, MAX_CLOCK_DIGITS), null)

    fun onStartMinutesChange(raw: String) = onStartTimeChange(null, digits(raw, MAX_CLOCK_DIGITS))

    private fun onStartTimeChange(hours: String?, minutes: String?) {
        val (currentHours, currentMinutes) = LogActivityFormat.splitClock(_draft.value.startedAtTime)
        val joined = LogActivityFormat.joinClock(
            hours ?: currentHours,
            minutes ?: currentMinutes,
        )
        clear { it.copy(startTimeError = null) }
        updateDraft { it.copy(startedAtTime = joined.ifEmpty { null }) }
    }

    fun onHoursChange(raw: String) {
        clear { it.copy(durationError = null) }
        updateDraft { it.copy(hours = digits(raw, MAX_CLOCK_DIGITS)) }
    }

    fun onMinutesChange(raw: String) {
        clear { it.copy(durationError = null) }
        updateDraft { it.copy(minutes = digits(raw, MAX_CLOCK_DIGITS)) }
    }

    fun onEffortChange(value: Int) {
        clear()
        updateDraft { it.copy(perceivedEffort = value) }
    }

    fun onNotesChange(raw: String) {
        clear()
        updateDraft { it.copy(notes = raw.take(ActivitySession.MAX_NOTES_LENGTH)) }
    }

    // --- Measurements -----------------------------------------------------------------

    /** Keeps exactly what was typed; both `.` and `,` reach the draft untouched (PRD 12). */
    fun onMetricChange(kind: MetricKind, raw: String) {
        val cleaned = if (kind == MetricKind.AVERAGE_PACE) raw else decimal(raw)
        transient.update {
            it.copy(metricErrors = it.metricErrors - kind.id, formError = null, saveError = null)
        }
        updateDraft { draft -> draft.withPresetDraft { it.withMetric(kind, cleaned) } }
    }

    /** The pace of PRD FR-ACTIVITY-007, typed in two boxes and kept joined as `m:ss`. */
    fun onPaceChange(minutes: String?, seconds: String?) {
        val current = _draft.value.presetDraft().metricInput(MetricKind.AVERAGE_PACE)
        val (currentMinutes, currentSeconds) = LogActivityFormat.splitClock(current)
        val joined = LogActivityFormat.joinClock(
            minutes?.let { digits(it, MAX_PACE_MINUTE_DIGITS) } ?: currentMinutes,
            seconds?.let { digits(it, MAX_CLOCK_DIGITS) } ?: currentSeconds,
        )
        onMetricChange(MetricKind.AVERAGE_PACE, joined)
    }

    // --- The `Other` builder ----------------------------------------------------------

    fun onOpenMovementPicker() = openPicker(CatalogTarget.MOVEMENT)

    fun onOpenEquipmentPicker() = openPicker(CatalogTarget.EQUIPMENT)

    fun onDismissPicker() = transient.update { it.copy(picker = null) }

    fun onPickerQueryChange(query: String) = transient.update { flags ->
        flags.copy(picker = flags.picker?.let { picker -> picker.copy(query = query, notice = null) })
    }

    /** Tapping a catalogue row. A known movement is stored by its stable id, never as a name. */
    fun onCatalogEntrySelected(id: String) {
        val target = transient.value.picker?.target ?: return
        when (target) {
            CatalogTarget.MOVEMENT -> {
                transient.update { it.copy(picker = null, movementError = null, formError = null) }
                updateDraft { draft ->
                    draft.withPresetDraft { it.copy(movementId = id, customMovementName = "") }
                }
            }

            CatalogTarget.EQUIPMENT -> addEquipment(EquipmentDraft(typeId = id))
        }
    }

    /**
     * PRD FR-ACTIVITY-008: the last resort, and the only path to `movement = other`. An
     * equipment already on the session is refused whatever its case.
     */
    fun onCreateFromSearch() {
        val picker = transient.value.picker ?: return
        val name = picker.trimmedQuery
        when (picker.target) {
            CatalogTarget.MOVEMENT -> when (val validated = ActivityValidation.validateCustomMovementName(name)) {
                is Validated.Valid -> {
                    transient.update { it.copy(picker = null, movementError = null, formError = null) }
                    updateDraft { draft ->
                        draft.withPresetDraft {
                            it.copy(
                                movementId = Movement.OTHER.id,
                                customMovementName = validated.value,
                            )
                        }
                    }
                }

                is Validated.Invalid -> notice(validated.message)
            }

            CatalogTarget.EQUIPMENT -> when (val validated = ActivityValidation.validateCustomEquipmentName(name)) {
                is Validated.Valid ->
                    addEquipment(EquipmentDraft(EquipmentType.OTHER.id, validated.value))

                is Validated.Invalid -> notice(validated.message)
            }
        }
    }

    fun onEnvironmentSelected(environment: ActivityEnvironment) {
        clear()
        updateDraft { draft -> draft.withPresetDraft { it.copy(environmentId = environment.id) } }
    }

    fun onEquipmentRemoved(index: Int) {
        updateDraft { draft ->
            draft.withPresetDraft { preset ->
                preset.copy(
                    equipment = preset.equipment.filterIndexed { position, _ -> position != index },
                )
            }
        }
    }

    private fun openPicker(target: CatalogTarget) = transient.update {
        it.copy(picker = pickerState(target, query = ""))
    }

    private fun notice(message: String) = transient.update { flags ->
        flags.copy(picker = flags.picker?.copy(notice = message))
    }

    /** The first occurrence wins, folded with [Locale.ROOT] (PRD FR-ACTIVITY-008). */
    private fun addEquipment(equipment: EquipmentDraft) {
        val existing = _draft.value.presetDraft().equipment
        if (existing.any { labelOf(it).folded() == labelOf(equipment).folded() }) {
            notice(LogActivityMessages.ALREADY_ADDED)
            return
        }
        transient.update { it.copy(picker = null) }
        updateDraft { draft ->
            draft.withPresetDraft { it.copy(equipment = it.equipment + equipment) }
        }
    }

    // --- Quick and detailed strength logging ------------------------------------------

    /**
     * PRD 9.1: dropping to the quick form is free while nothing has been written, and asks
     * before it discards sets that already exist on a stored session. The drafted exercises
     * themselves stay put either way — the toggle stays reversible until the save.
     */
    fun onQuickLogSelected() {
        if (!_draft.value.detailed) return
        if (transient.value.storedExerciseCount > 0) {
            transient.update { it.copy(quickLogConfirmationVisible = true) }
            return
        }
        clear()
        updateDraft { it.copy(detailed = false) }
    }

    fun onConfirmQuickLog() {
        transient.update { it.copy(quickLogConfirmationVisible = false, formError = null) }
        updateDraft { it.copy(detailed = false) }
    }

    fun onCancelQuickLog() = transient.update { it.copy(quickLogConfirmationVisible = false) }

    /** The screen opens the editor right after; the draft it will edit is already this one. */
    fun onDetailedLogSelected() {
        clear()
        updateDraft { it.copy(detailed = true) }
    }

    /**
     * The seam of contract section 5: the strength editor is a pure function of the draft, so
     * every one of its actions arrives here as a value and leaves as a new draft.
     */
    fun onStrengthEdit(edit: StrengthEdit) {
        // TODO(strength chunk): the body is `updateDraft { StrengthDraftEditor.apply(it, edit) }`.
        // `StrengthDraftEditor` is owned by the strength editor chunk and had not landed when
        // this file was written; [StrengthEdit] and this signature are frozen so that chunk can
        // be written against them, and nothing else in this ViewModel changes when it does.
    }

    // --- Saving -----------------------------------------------------------------------

    /**
     * PRD FR-ACTIVITY-010. Only what the active preset exposes is written: an incline typed
     * under `Treadmill walk` and abandoned for `Run` is simply never read.
     */
    fun save() {
        if (transient.value.isSaving) return
        val draft = _draft.value
        val prepared = validate(draft) ?: return
        transient.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val result = runCatching {
                activities.save(detailOf(draft, prepared))
            }
            transient.update { flags ->
                if (result.isSuccess) {
                    flags.copy(isSaving = false, justSaved = true)
                } else {
                    flags.copy(isSaving = false, saveError = LogActivityMessages.SAVE_FAILED)
                }
            }
        }
    }

    /**
     * Contract decision 8: the confirmation plays on the button and the return follows it. The
     * write is already committed when it starts, so only the return is deferred.
     *
     * The draft itself is left alone — the screen is about to leave — and the marker is what is
     * dropped, so the next visit opens a form with nothing on it.
     */
    fun onSaveConfirmationFinished() {
        savedState.remove<String>(KEY_STARTED_FOR)
        transient.update { it.copy(justSaved = false) }
    }

    // --- Deleting ---------------------------------------------------------------------

    fun onRequestDelete() = transient.update { it.copy(deleteConfirmationVisible = true) }

    fun onCancelDelete() = transient.update { it.copy(deleteConfirmationVisible = false) }

    /** PRD FR-ACTIVITY-011: measurements, equipment, exercises and sets go with it. */
    fun onConfirmDelete() {
        val id = _draft.value.editingSessionId ?: return
        transient.update { it.copy(deleteConfirmationVisible = false, saveError = null) }
        viewModelScope.launch {
            val result = runCatching { activities.delete(ActivityId(id)) }
            transient.update { flags ->
                if (result.isSuccess) {
                    flags.copy(justDeleted = true)
                } else {
                    flags.copy(saveError = LogActivityMessages.DELETE_FAILED)
                }
            }
        }
    }

    fun onDeleteConfirmationFinished() {
        savedState.remove<String>(KEY_STARTED_FOR)
        transient.update { it.copy(justDeleted = false) }
    }

    // --- Validation -------------------------------------------------------------------

    /** Null when something is wrong, with the messages already on the fields (PRD 12). */
    private fun validate(draft: ActivityDraft): Prepared? {
        val preset = draft.preset
        val presetDraft = draft.presetDraft()

        val date = draft.startedOn.toLocalDateOrNull() ?: today()
        val validatedDate = ActivityValidation.validateStartedOn(date, today())
        val validatedTime = validateStartTime(draft.startedAtTime)
        val validatedDuration = ActivityValidation.validateDuration(draft.hours, draft.minutes)
        val validatedEffort = ActivityValidation.validatePerceivedEffort(draft.perceivedEffort)

        val metrics = draft.activeMetricInputs()
            .mapValues { (kind, raw) -> ActivityValidation.validateMetric(kind, raw) }
        val metricErrors = metrics
            .mapNotNull { (kind, validated) ->
                (validated as? Validated.Invalid)?.let { kind.id to it.message }
            }
            .toMap()

        val movement = movementOf(draft)
        val movementMissing = preset.movement == null && movement == null
        val needsName = movement == Movement.OTHER
        val validatedName = if (needsName) {
            ActivityValidation.validateCustomMovementName(presetDraft.customMovementName)
        } else {
            Validated.Valid("")
        }
        val movementError = when {
            movementMissing -> LogActivityMessages.MOVEMENT_REQUIRED
            !validatedName.isValid -> (validatedName as Validated.Invalid).message
            else -> null
        }

        val detailed = draft.detailed && preset.offersStrengthDetail
        val setsMissing = detailed && !draft.hasAnyValidSet()

        val summary = listOfNotNull(
            (validatedDate as? Validated.Invalid)?.message,
            (validatedTime as? Validated.Invalid)?.message,
            (validatedDuration as? Validated.Invalid)?.message,
            movementError,
            metricErrors.values.firstOrNull(),
            (validatedEffort as? Validated.Invalid)?.message,
            LogActivityMessages.NO_VALID_SET.takeIf { setsMissing },
        ).firstOrNull()

        transient.update {
            it.copy(
                dateError = (validatedDate as? Validated.Invalid)?.message,
                startTimeError = (validatedTime as? Validated.Invalid)?.message,
                durationError = (validatedDuration as? Validated.Invalid)?.message,
                movementError = movementError,
                metricErrors = metricErrors,
                formError = summary,
                saveError = null,
            )
        }
        if (summary != null) return null

        return Prepared(
            date = validatedDate.valueOrNull ?: date,
            startedAtTime = validatedTime.valueOrNull,
            duration = requireNotNull(validatedDuration.valueOrNull),
            effort = validatedEffort.valueOrNull,
            movement = movement ?: Movement.OTHER,
            customMovementName = presetDraft.customMovementName.trim().takeIf { needsName },
            environment = environmentOf(draft),
            metrics = metrics.mapNotNull { (kind, validated) ->
                validated.valueOrNull?.let { ActivityMetric(kind, it, preset.sourceOf(kind)) }
            },
            equipment = equipmentOf(draft),
            detailed = detailed,
        )
    }

    /** The optional start time of PRD 8.2; blank stays distinct from midnight. */
    private fun validateStartTime(raw: String?): Validated<LocalTime?> {
        val (hours, minutes) = LogActivityFormat.splitClock(raw)
        if (hours.isBlank() && minutes.isBlank()) return Validated.Valid(null)
        val typedHours = hours.ifBlank { "0" }.toIntOrNull()
        val typedMinutes = minutes.ifBlank { "0" }.toIntOrNull()
        if (typedHours == null || typedMinutes == null ||
            typedHours !in 0..23 || typedMinutes !in 0..59
        ) {
            return Validated.Invalid(LogActivityMessages.START_TIME_ERROR)
        }
        return Validated.Valid(LocalTime.of(typedHours, typedMinutes))
    }

    private fun movementOf(draft: ActivityDraft): Movement? =
        draft.preset.movement ?: draft.presetDraft().movementId?.let { Movement.fromId(it) }

    private fun environmentOf(draft: ActivityDraft): ActivityEnvironment =
        if (draft.preset.choosesEnvironment) {
            draft.presetDraft().environmentId?.let { ActivityEnvironment.fromId(it) }
                ?: ActivityEnvironment.UNKNOWN
        } else {
            draft.preset.environment
        }

    /**
     * The preset's own machine first, then the tags. Read whatever the preset shows or not, so
     * reopening a treadmill walk that also carried a heart-rate strap writes it back.
     */
    private fun equipmentOf(draft: ActivityDraft): List<SessionEquipment> {
        val implicit = draft.preset.equipment?.let { listOf(SessionEquipment(it)) }.orEmpty()
        val chosen = draft.presetDraft().equipment.map { chip ->
            val type = EquipmentType.fromId(chip.typeId)
            SessionEquipment(
                equipmentType = type,
                customName = chip.customName.trim().takeIf { type == EquipmentType.OTHER && it.isNotEmpty() },
            )
        }
        return ActivityValidation.distinctEquipment(implicit + chosen)
            .filter(ActivityValidation::isNamingConsistent)
    }

    private suspend fun detailOf(draft: ActivityDraft, prepared: Prepared): ActivitySessionDetail =
        ActivitySessionDetail(
            session = ActivitySession(
                id = draft.editingSessionId?.let(::ActivityId) ?: ActivityId.random(),
                movement = prepared.movement,
                startedOn = prepared.date,
                duration = prepared.duration,
                customMovementName = prepared.customMovementName,
                environment = prepared.environment,
                startedAtTime = ActivityValidation.normalizeStartTime(prepared.startedAtTime),
                perceivedEffort = prepared.effort,
                notes = ActivityValidation.normalizeNotes(draft.notes),
                source = ActivitySource.MANUAL,
            ),
            metrics = ActivityMetrics.of(prepared.metrics),
            equipment = prepared.equipment,
            // PRD 9.1: a quick log writes no exercise at all, however many the draft still holds.
            exercises = if (prepared.detailed) {
                StrengthRules.persistableExercises(resolveExercises(draft))
            } else {
                emptyList()
            },
        )

    /**
     * PRD 9.2: a name already in the catalogue reuses its definition rather than adding a
     * second one, which is why the id is resolved here rather than trusted from the draft.
     */
    private suspend fun resolveExercises(draft: ActivityDraft): List<StrengthExerciseDetail> =
        draft.exercises.mapIndexed { index, exercise ->
            val mode = TrackingMode.fromId(exercise.trackingModeId)
            val definition = catalog.findById(ExerciseDefinitionId(exercise.definitionId))
                ?: catalog.findOrCreate(
                    name = exercise.name,
                    trackingMode = mode,
                    equipment = exercise.equipmentId?.let { EquipmentType.fromId(it) },
                )
            StrengthExerciseDetail(
                exercise = StrengthExercise(
                    id = StrengthExerciseId.random(),
                    position = index,
                    notes = exercise.notes.trim().takeIf { it.isNotEmpty() },
                ),
                definition = definition,
                sets = exercise.sets.mapIndexed { position, set -> strengthSetOf(position, set) },
            )
        }

    // --- State ------------------------------------------------------------------------

    private fun build(
        draft: ActivityDraft,
        flags: Transient,
        hapticsEnabled: Boolean,
    ): LogActivityUiState {
        val preset = draft.preset
        val presetDraft = draft.presetDraft()
        val (startHours, startMinutes) = LogActivityFormat.splitClock(draft.startedAtTime)
        return LogActivityUiState(
            isEditing = draft.editingSessionId != null,
            isLoading = flags.isLoading,
            preset = preset,
            today = today(),
            date = draft.startedOn.toLocalDateOrNull() ?: today(),
            startHours = startHours,
            startMinutes = startMinutes,
            hours = draft.hours,
            minutes = draft.minutes,
            perceivedEffort = draft.perceivedEffort,
            notes = draft.notes,
            detailed = draft.detailed,
            exerciseCount = draft.exercises.size,
            storedExerciseCount = flags.storedExerciseCount,
            metrics = preset.metrics.map { kind ->
                MetricFieldState(
                    kind = kind,
                    input = presetDraft.metricInput(kind),
                    error = flags.metricErrors[kind.id],
                    source = preset.sourceOf(kind),
                )
            },
            movement = presetDraft.movementId?.let { Movement.fromId(it) },
            customMovementName = presetDraft.customMovementName,
            environment = presetDraft.environmentId?.let { ActivityEnvironment.fromId(it) }
                ?: preset.environment,
            equipment = presetDraft.equipment.mapIndexed { index, chip ->
                EquipmentChipState(index, labelOf(chip))
            },
            dateError = flags.dateError,
            startTimeError = flags.startTimeError,
            durationError = flags.durationError,
            movementError = flags.movementError,
            formError = flags.formError,
            saveError = flags.saveError,
            isSaving = flags.isSaving,
            datePickerVisible = flags.datePickerVisible,
            picker = flags.picker?.let { refresh(it, draft) },
            deleteConfirmationVisible = flags.deleteConfirmationVisible,
            quickLogConfirmationVisible = flags.quickLogConfirmationVisible,
            justSaved = flags.justSaved,
            justDeleted = flags.justDeleted,
            hapticsEnabled = hapticsEnabled,
        )
    }

    /** The catalogue is filtered here rather than in composition (contract section 6). */
    private fun refresh(picker: CatalogPickerState, draft: ActivityDraft): CatalogPickerState =
        picker.copy(results = pickerState(picker.target, picker.query, draft).results)

    private fun pickerState(
        target: CatalogTarget,
        query: String,
        draft: ActivityDraft = _draft.value,
    ): CatalogPickerState {
        val needle = query.trim().folded()
        val presetDraft = draft.presetDraft()
        val results = when (target) {
            CatalogTarget.MOVEMENT -> ActivityPreset.OTHER_CATALOGUE
                .filter { needle.isEmpty() || it.displayName.folded().contains(needle) }
                .map { movement ->
                    CatalogEntry(
                        id = movement.id,
                        name = movement.displayName,
                        meta = LogActivityFormat.meta(movement),
                        selected = presetDraft.movementId == movement.id,
                    )
                }

            CatalogTarget.EQUIPMENT -> EquipmentType.entries
                .filterNot { it == EquipmentType.OTHER }
                .filter { needle.isEmpty() || it.displayName.folded().contains(needle) }
                .map { type ->
                    CatalogEntry(
                        id = type.id,
                        name = type.displayName,
                        meta = LogActivityFormat.meta(type),
                        selected = presetDraft.equipment.any {
                            labelOf(it).folded() == type.displayName.folded()
                        },
                    )
                }
        }
        return CatalogPickerState(target = target, query = query, results = results)
    }

    // --- Draft plumbing ---------------------------------------------------------------

    private fun replaceDraft(draft: ActivityDraft) {
        _draft.value = draft
        savedState[KEY_DRAFT] = draft.toJson()
    }

    private fun updateDraft(block: (ActivityDraft) -> ActivityDraft) =
        replaceDraft(block(_draft.value))

    /** Any edit invalidates the summary and the last failure; nothing else is touched. */
    private fun clear(block: (Transient) -> Transient = { it }) =
        transient.update { block(it).copy(formError = null, saveError = null) }

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val KEY_DRAFT = "activity.log.draft"
        private const val KEY_STARTED_FOR = "activity.log.startedFor"

        /** `99` hours, `59` minutes, `59` seconds — every clock box holds two digits. */
        private const val MAX_CLOCK_DIGITS = 2

        /** A pace slower than 99:59 per kilometre is a typing accident (PRD FR-ACTIVITY-007). */
        private const val MAX_PACE_MINUTE_DIGITS = 2

        /** Enough for any measurement anyone types, and short enough to bound the arithmetic. */
        private const val MAX_NUMBER_LENGTH = 8

        fun digits(raw: String, max: Int): String = raw.filter(Char::isDigit).take(max)

        /**
         * Both separators reach the draft (PRD 12); everything else is refused at the keystroke
         * so no field can hold text a save would later have to explain.
         */
        fun decimal(raw: String): String = raw
            .filter { it.isDigit() || it == '.' || it == ',' }
            .take(MAX_NUMBER_LENGTH)

        /** `"I".lowercase()` is `"ı"` on a Turkish phone, so every fold names its locale. */
        fun String.folded(): String = trim().lowercase(Locale.ROOT)

        fun labelOf(chip: EquipmentDraft): String = chip.customName.trim()
            .ifEmpty { EquipmentType.fromId(chip.typeId).displayName }

        fun String.toLocalDateOrNull(): LocalDate? =
            runCatching { LocalDate.parse(this) }.getOrNull()

        /** One drafted set, parsed. An unreadable field is an absent one, never a zero. */
        fun strengthSetOf(position: Int, set: SetDraft): StrengthSet = StrengthSet(
            id = StrengthSetId.random(),
            position = position,
            setType = SetType.fromId(set.setTypeId),
            repetitions = ActivityValidation.validateRepetitions(set.reps).valueOrNull,
            load = ActivityValidation.validateLoad(set.loadKg).valueOrNull,
            duration = ActivityValidation.validateSetDuration(set.durationSeconds).valueOrNull,
            perceivedEffort = set.perceivedEffort?.let { PerceivedEffort.ofOrNull(it) },
        )

        /**
         * PRD FR-ACTIVITY-009: a detailed session needs one complete set. Read off the draft's
         * own tracking mode so the check costs no database round trip.
         */
        fun ActivityDraft.hasAnyValidSet(): Boolean = exercises.any { exercise ->
            val mode = TrackingMode.fromId(exercise.trackingModeId)
            exercise.sets.withIndex().any { (index, set) -> mode.isValid(strengthSetOf(index, set)) }
        }

        /**
         * The key both screens ask for. `Log activity` and `Strength session` share one
         * instance because they share one draft (contract section 5); the store is the hosting
         * activity's, which is what carries it across a rotation.
         */
        const val KEY: String = "activity.log"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                LogActivityViewModel(
                    activities = app.container.activityRepository,
                    catalog = app.container.exerciseCatalogRepository,
                    preferences = app.container.userPreferencesRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }

    /** Everything a save attempt turned the typed text into, once it all parsed. */
    private class Prepared(
        val date: LocalDate,
        val startedAtTime: LocalTime?,
        val duration: ActivityDuration,
        val effort: PerceivedEffort?,
        val movement: Movement,
        val customMovementName: String?,
        val environment: ActivityEnvironment,
        val metrics: List<ActivityMetric>,
        val equipment: List<SessionEquipment>,
        val detailed: Boolean,
    )

    /** Screen state that is not the draft: messages, open panels, and the two confirmations. */
    private data class Transient(
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val dateError: String? = null,
        val startTimeError: String? = null,
        val durationError: String? = null,
        val movementError: String? = null,
        val metricErrors: Map<String, String> = emptyMap(),
        val formError: String? = null,
        val saveError: String? = null,
        val datePickerVisible: Boolean = false,
        val picker: CatalogPickerState? = null,
        val deleteConfirmationVisible: Boolean = false,
        val quickLogConfirmationVisible: Boolean = false,
        val storedExerciseCount: Int = 0,
        val justSaved: Boolean = false,
        val justDeleted: Boolean = false,
    )
}

/**
 * The shared instance of the form's ViewModel.
 *
 * Both `Log activity` and the detailed strength editor call this and get the same object: the
 * draft they edit is one draft (PRD 9.1), and neither screen may create one of its own.
 */
@Composable
fun logActivityViewModel(): LogActivityViewModel =
    viewModel(key = LogActivityViewModel.KEY, factory = LogActivityViewModel.Factory)
