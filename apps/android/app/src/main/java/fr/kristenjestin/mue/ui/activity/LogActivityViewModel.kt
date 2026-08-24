package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.ActivityValidation
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
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

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
 *
 * The same form is where a finished timer is reviewed (PRD_ACTIVITY_TIMER FR-TIMER-005). That
 * adds a second store rather than a second screen: `SavedStateHandle` still carries the form
 * across a rotation, and the draft row carries it across a closed app, because several timed
 * drafts can wait at once and a handle belongs to whichever one was last on screen (PRD 8.2).
 */
class LogActivityViewModel(
    private val activities: ActivityRepository,
    private val drafts: TimedActivityRepository,
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

    /** PRD FR-ACTIVITY-009: what `Add exercise` lists, most recently used first. */
    val catalogue: StateFlow<List<ExerciseDefinition>> = catalog.observeCatalogue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * PRD 11.4, keyed by `ExerciseDefinitionId.value`.
     *
     * Only the set of drafted exercises matters, so the queries run again when one is added or
     * removed and not on every keystroke. The session being edited is excluded, or a re-opened
     * session would quote itself back as its own last performance.
     */
    val lastPerformances: StateFlow<Map<String, LastPerformance>> = _draft
        .map { draft ->
            draft.exercises.map(ExerciseDraft::definitionId).distinct() to draft.editingSessionId
        }
        .distinctUntilChanged()
        .map { (definitionIds, sessionId) ->
            definitionIds.mapNotNull { id ->
                runCatching {
                    activities.findLastPerformance(
                        exercise = ExerciseDefinitionId(id),
                        excludingSession = sessionId?.let(::ActivityId),
                    )
                }.getOrNull()?.let { id to it }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

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

    init {
        /*
         * PRD 8.2: the review form's state is rewritten at every significant change rather than
         * held back until `Save activity`, so closing the app loses nothing that was typed.
         *
         * A `StateFlow` is what makes that affordable. It conflates, so a burst of keystrokes
         * costs one write per value the typing settles on; and it is collected in sequence, so
         * the last value is also the last one written — which two racing launches could not
         * promise. A failure is dropped on purpose: the typed columns still hold the movement
         * and the measured duration, and a form nobody can save is a worse outcome than a
         * correction that has to be made again.
         */
        viewModelScope.launch {
            _draft.collect { draft ->
                val id = draft.timedDraftId ?: return@collect
                runCatching {
                    drafts.saveReviewFormState(
                        id = TimedDraftId(id),
                        state = draft.toJson(),
                        schemaVersion = ActivityDraft.SCHEMA_VERSION,
                    )
                }
            }
        }
    }

    // --- Opening the form -------------------------------------------------------------

    /**
     * Called by the screen on every entry, and idempotent by design.
     *
     * Returning from the strength editor recomposes the form from scratch, so a naive reset
     * here would wipe the very draft the two screens share. The marker is what tells a genuine
     * new visit from a return: it is dropped once a save or a delete has landed, which is what
     * makes the next `Log activity` open on a blank form.
     *
     * A confirmation still playing is neither. The save marker is dropped on the write rather
     * than on the discharge that follows it, so a rotation during that second would otherwise
     * look like a new visit and reopen a blank form on top of a session already written. The
     * flags outlive the rotation with this ViewModel, so the discharge simply resumes and the
     * return happens as it was going to.
     */
    fun start(sessionId: ActivityId?, draftId: TimedDraftId? = null) {
        if (transient.value.justSaved || transient.value.justDeleted) return
        val marker = markerOf(sessionId, draftId)
        if (savedState.get<String>(KEY_STARTED_FOR) == marker) return
        savedState[KEY_STARTED_FOR] = marker
        transient.value = Transient(isLoading = sessionId != null || draftId != null)
        when {
            // FR-TIMER-005 wins over an id that should never arrive with it: a timed draft is
            // reviewed into a new session, never into an existing one.
            draftId != null -> viewModelScope.launch { prefillFromTimer(draftId) }
            sessionId != null -> viewModelScope.launch { prefill(sessionId) }
            else -> replaceDraft(ActivityDraft())
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

    /**
     * FR-TIMER-005: the form opens on what the timer measured, and FR-TIMER-008 lets it be
     * opened again as often as the person likes until they save.
     *
     * A draft that is no longer there opens a blank form rather than an empty review: it was
     * saved from another window, or discarded, and there is nothing left to review.
     */
    private suspend fun prefillFromTimer(id: TimedDraftId) {
        val timed = runCatching { drafts.findDraft(id) }.getOrNull()
        replaceDraft(if (timed == null) ActivityDraft() else reviewDraftOf(timed))
        transient.update { it.copy(isLoading = false) }
    }

    /**
     * PRD 8.2, and the whole of it.
     *
     * A form state this build wrote is decoded and gives the typing back. Anything else — a
     * blob that will not parse, or one written under another schema version — is **not**
     * decoded at all, and the form is rebuilt from the typed columns instead. Only the typing
     * is lost that way; the movement, the equipment, the start instant and above all the
     * measured duration come from columns no serialisation format can spoil.
     *
     * The draft id is stamped on afterwards either way, so a blob that somehow arrived without
     * one still saves through `commitToSession` rather than as a hand-typed session.
     */
    private fun reviewDraftOf(timed: TimedActivityDraft): ActivityDraft {
        val restored = timed.reviewFormState
            ?.takeIf { timed.reviewFormSchemaVersion == ActivityDraft.SCHEMA_VERSION }
            ?.let(ActivityDraft::fromJsonOrNull)
        return (restored ?: rebuiltFrom(timed)).copy(timedDraftId = timed.id.value)
    }

    /**
     * The typed columns of PRD 8.2, as a form.
     *
     * The start time is truncated to the minute here because `activity_sessions.started_at_time`
     * is `HH:mm` and FR-TIMER-005 makes that a fact about the value rather than about its
     * display: what the timer screen promised and what the form prefills have to be the same
     * minute. The duration is where the seconds survive, which is the point of FR-TIMER-006.
     *
     * No measurement is prefilled: FR-TIMER-005 forbids it, and a distance Mue never observed
     * would be a number the user is invited to accept rather than to enter.
     */
    private fun rebuiltFrom(timed: TimedActivityDraft): ActivityDraft {
        val preset = ActivityPreset.of(timed.movement, timed.equipment)
        val duration = timed.accumulatedActive
        return ActivityDraft(
            timedDraftId = timed.id.value,
            presetId = preset.id,
            startedOn = timed.startedOn.toString(),
            startedAtTime = timed.startedAtLocalTime
                .truncatedTo(ChronoUnit.MINUTES)
                .format(LogActivityFormat.TIME),
            hours = duration.hoursPart.toString(),
            minutes = duration.minutesPart.toString(),
            seconds = duration.secondsPart.toString(),
            byPreset = mapOf(preset.id to presetDraftOf(preset, timed)),
        )
    }

    /** The preset's own machine stays implicit in its tile, exactly as it does for a session. */
    private fun presetDraftOf(preset: ActivityPreset, timed: TimedActivityDraft): PresetDraft =
        PresetDraft(
            movementId = timed.movement.id,
            customMovementName = timed.customMovementName.orEmpty(),
            environmentId = timed.environment.id,
            equipment = timed.equipment
                .filterNot { it.equipmentType == preset.equipment && it.customName == null }
                .map { EquipmentDraft(it.equipmentType.id, it.customName.orEmpty()) },
        )

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

    fun onOpenTimePicker() = transient.update { it.copy(timePickerVisible = true) }

    fun onDismissTimePicker() = transient.update { it.copy(timePickerVisible = false) }

    /** PRD 8.2: null clears the time and stays distinct from midnight, which is a real 00:00. */
    fun onStartTimeSelected(time: LocalTime?) {
        clear { it.copy(timePickerVisible = false, startTimeError = null) }
        updateDraft { it.copy(startedAtTime = time?.format(LogActivityFormat.TIME)) }
    }

    fun onHoursChange(raw: String) {
        clear { it.copy(durationError = null) }
        updateDraft { it.copy(hours = digits(raw, MAX_CLOCK_DIGITS)) }
    }

    fun onMinutesChange(raw: String) {
        clear { it.copy(durationError = null) }
        updateDraft { it.copy(minutes = digits(raw, MAX_CLOCK_DIGITS)) }
    }

    // --- The measured duration (FR-TIMER-006) -----------------------------------------

    fun onOpenDurationPicker() = transient.update { it.copy(durationPickerVisible = true) }

    fun onDismissDurationPicker() = transient.update { it.copy(durationPickerVisible = false) }

    /**
     * FR-TIMER-006: the summary's three-field correction, and the only writer of the seconds.
     *
     * The three parts are stored as text like everything else in the draft, so what the wheels
     * were left on survives a process death. Nothing is refused here — the bounds belong to
     * `ActivityValidation.validateTimedDuration`, beside the action that would apply them.
     */
    fun onTimedDurationSelected(hours: Int, minutes: Int, seconds: Int) {
        clear { it.copy(durationPickerVisible = false, durationError = null) }
        updateDraft {
            it.copy(
                hours = hours.toString(),
                minutes = minutes.toString(),
                seconds = seconds.toString(),
            )
        }
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
        val cleaned = if (kind == MetricKind.AVERAGE_PACE) {
            raw
        } else {
            decimal(raw, kind.displayDecimals)
        }
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

    /**
     * Tapping a catalogue row. A known movement is stored by its stable id, never as a name.
     *
     * The two catalogues answer different questions, so they close differently. A movement is
     * one choice and the sheet leaves with it. Equipment is `Select one or more`
     * (FR-ACTIVITY-008), so a row toggles and the sheet stays put until it is dismissed —
     * closing on each pick made adding a second item cost a second trip through the sheet.
     */
    fun onCatalogEntrySelected(id: String) {
        val target = transient.value.picker?.target ?: return
        when (target) {
            CatalogTarget.MOVEMENT -> {
                transient.update { it.copy(picker = null, movementError = null, formError = null) }
                updateDraft { draft ->
                    draft.withPresetDraft { it.copy(movementId = id, customMovementName = "") }
                }
            }

            CatalogTarget.EQUIPMENT -> toggleEquipment(EquipmentDraft(typeId = id))
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
                    createEquipment(EquipmentDraft(EquipmentType.OTHER.id, validated.value))

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

    /**
     * A catalogue row is a switch: tapping an unselected one adds the equipment, tapping a
     * selected one takes it back off. Either way the sheet stays open, so a session that used
     * three machines is three taps rather than three journeys.
     *
     * Names are compared folded with [Locale.ROOT] (PRD FR-ACTIVITY-008), so a `Treadmill`
     * added by the catalogue and a `treadmill` typed by hand are the same one item.
     */
    private fun toggleEquipment(equipment: EquipmentDraft) {
        val folded = labelOf(equipment).folded()
        updateDraft { draft ->
            draft.withPresetDraft { preset ->
                preset.copy(
                    equipment = if (preset.equipment.any { labelOf(it).folded() == folded }) {
                        preset.equipment.filterNot { labelOf(it).folded() == folded }
                    } else {
                        preset.equipment + equipment
                    },
                )
            }
        }
    }

    /**
     * The `Create` footer is not a row and does not toggle: asking to create a name the session
     * already carries is a mistake to point out, not an instruction to remove it. On success the
     * search is cleared, which is both the acknowledgement and the state the next pick needs.
     */
    private fun createEquipment(equipment: EquipmentDraft) {
        val existing = _draft.value.presetDraft().equipment
        if (existing.any { labelOf(it).folded() == labelOf(equipment).folded() }) {
            notice(LogActivityMessages.ALREADY_ADDED)
            return
        }
        onPickerQueryChange("")
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
    fun onStrengthEdit(edit: StrengthEdit) = updateDraft { StrengthDraftEditor.apply(it, edit) }

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
                val detail = detailOf(draft, prepared)
                val timed = draft.timedDraftId
                // FR-TIMER-007: one session created and the draft deleted, in one transaction.
                // A failure leaves both exactly where they were (PRD 12), which is why the two
                // are never written by two calls from here.
                if (timed == null) {
                    activities.save(detail)
                } else {
                    drafts.commitToSession(TimedDraftId(timed), detail)
                }
            }
            // Dropped on the write rather than on the confirmation that follows it: the
            // detailed editor saves through the same path but leaves by its own callback, and
            // nothing can re-enter the form between the two moments anyway.
            if (result.isSuccess) savedState.remove<String>(KEY_STARTED_FOR)
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
        // PRD 17: the one-minute floor is the manual form's, which cannot express seconds. A
        // measured session is held to one second instead, so a `Finish` pressed after forty of
        // them records a real session rather than losing what was measured.
        val validatedDuration = if (draft.isTimedReview) {
            ActivityValidation.validateTimedDuration(draft.hours, draft.minutes, draft.seconds)
        } else {
            ActivityValidation.validateDuration(draft.hours, draft.minutes)
        }
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
        val setsMissing = detailed && !StrengthDraftEditor.hasAnyValidSet(draft)

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

    /**
     * The optional start time of PRD 8.2; blank stays distinct from midnight.
     *
     * The picker can only produce a real minute of the day, so this is a guard rather than a
     * rule the form exercises: the draft crosses `SavedStateHandle` as text, and a string this
     * app did not write is refused here rather than silently dropped.
     */
    private fun validateStartTime(raw: String?): Validated<LocalTime?> = when {
        raw.isNullOrBlank() -> Validated.Valid(null)
        else -> LogActivityFormat.timeOrNull(raw)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(LogActivityMessages.START_TIME_ERROR)
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
                // FR-TIMER-007: what tells a chronometered session from a typed one, and what
                // the `Start again` shortcut of the timer's PRD 6.1 looks for.
                source = if (draft.isTimedReview) ActivitySource.TIMER else ActivitySource.MANUAL,
            ),
            metrics = ActivityMetrics.of(prepared.metrics),
            equipment = prepared.equipment,
            // PRD 9.1: a quick log writes no exercise at all, however many the draft still holds.
            exercises = if (prepared.detailed) {
                resolveDefinitions(StrengthDraftEditor.persistableExercises(draft))
            } else {
                emptyList()
            },
        )

    /**
     * PRD 9.2: an exercise the draft carries may name a definition that is not in the catalogue
     * yet — the picker mints one for a custom name without writing it — and
     * `strength_exercises.exercise_definition_id` is a restricted foreign key, so the row has to
     * exist before a session points at it. A name already in the catalogue reuses its definition
     * rather than adding a second one, which is why the id is resolved here rather than trusted
     * from the draft.
     *
     * It runs *after* [StrengthDraftEditor.persistableExercises] so an exercise that invariant
     * drops never leaves a definition behind for a set nobody kept.
     */
    private suspend fun resolveDefinitions(
        exercises: List<StrengthExerciseDetail>,
    ): List<StrengthExerciseDetail> = exercises.map { detail ->
        val definition = catalog.findById(detail.definition.id)
            ?: catalog.findOrCreate(
                name = detail.definition.name,
                trackingMode = detail.definition.trackingMode,
                equipment = detail.definition.equipment,
            )
        detail.copy(definition = definition)
    }

    // --- State ------------------------------------------------------------------------

    private fun build(
        draft: ActivityDraft,
        flags: Transient,
        hapticsEnabled: Boolean,
    ): LogActivityUiState {
        val preset = draft.preset
        val presetDraft = draft.presetDraft()
        return LogActivityUiState(
            isEditing = draft.editingSessionId != null,
            isLoading = flags.isLoading,
            preset = preset,
            today = today(),
            date = draft.startedOn.toLocalDateOrNull() ?: today(),
            startTime = LogActivityFormat.timeOrNull(draft.startedAtTime),
            hours = draft.hours,
            minutes = draft.minutes,
            seconds = draft.seconds,
            isTimedReview = draft.isTimedReview,
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
            timePickerVisible = flags.timePickerVisible,
            durationPickerVisible = flags.durationPickerVisible,
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
         * What a visit is remembered by, so returning from the strength editor is told from a
         * genuinely new entry.
         *
         * The two kinds of id are prefixed rather than merged: they come from different tables,
         * and a session and a timed draft sharing a value would otherwise open the wrong form.
         */
        private fun markerOf(sessionId: ActivityId?, draftId: TimedDraftId?): String = when {
            draftId != null -> "draft:${draftId.value}"
            sessionId != null -> "session:${sessionId.value}"
            else -> ""
        }

        /**
         * Both separators reach the draft (PRD 12); everything else is refused at the keystroke
         * so no field can hold text a save would later have to explain.
         *
         * The fraction is cut at [maxDecimals], the precision the field renders back, so the
         * box can never hold a value it would have to round on the way out — which is what
         * keeps re-opening a session and saving it again a no-op. The clock boxes cap their
         * digits the same way.
         */
        fun decimal(raw: String, maxDecimals: Int): String {
            val filtered = raw
                .filter { it.isDigit() || it == '.' || it == ',' }
                .take(MAX_NUMBER_LENGTH)
            val separator = filtered.indexOfFirst { it == '.' || it == ',' }
            return when {
                separator < 0 -> filtered
                maxDecimals == 0 -> filtered.take(separator)
                else -> filtered.take(separator + 1 + maxDecimals)
            }
        }

        /** `"I".lowercase()` is `"ı"` on a Turkish phone, so every fold names its locale. */
        fun String.folded(): String = trim().lowercase(Locale.ROOT)

        fun labelOf(chip: EquipmentDraft): String = chip.customName.trim()
            .ifEmpty { EquipmentType.fromId(chip.typeId).displayName }

        fun String.toLocalDateOrNull(): LocalDate? =
            runCatching { LocalDate.parse(this) }.getOrNull()

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
                    drafts = app.container.timer.timedActivityRepository,
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
        val timePickerVisible: Boolean = false,
        val durationPickerVisible: Boolean = false,
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

/**
 * The strength editor, backed by the form's own draft (contract section 5).
 *
 * `StrengthSessionScreen` defaults to a host that keeps a draft of its own and writes nothing;
 * passing this instead is what makes `Quick log` and `Detailed log` two views of one session
 * (PRD 9.1). Every mutation the editor asks for arrives as a [StrengthEdit] and leaves through
 * `StrengthDraftEditor`, so this adapter holds no state and decides nothing.
 */
@Composable
fun rememberSharedStrengthSessionState(
    viewModel: LogActivityViewModel = logActivityViewModel(),
): StrengthSessionState {
    val draft = viewModel.draft.collectAsStateWithLifecycle()
    val catalogue = viewModel.catalogue.collectAsStateWithLifecycle()
    val performances = viewModel.lastPerformances.collectAsStateWithLifecycle()
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    return remember(viewModel) {
        SharedStrengthSessionState(viewModel, draft, catalogue, performances, state)
    }
}

@Stable
private class SharedStrengthSessionState(
    private val viewModel: LogActivityViewModel,
    private val draftState: State<ActivityDraft>,
    private val catalogueState: State<List<ExerciseDefinition>>,
    private val performanceState: State<Map<String, LastPerformance>>,
    private val uiState: State<LogActivityUiState>,
) : StrengthSessionState {

    override val draft: ActivityDraft get() = draftState.value

    override val catalogue: List<ExerciseDefinition> get() = catalogueState.value

    override val lastPerformances: Map<String, LastPerformance>
        get() = performanceState.value

    /** Contract decision 8: the discharge plays first, and the editor leaves after it. */
    override val saved: Boolean get() = uiState.value.justSaved

    override val hapticsEnabled: Boolean get() = uiState.value.hapticsEnabled

    override fun edit(edit: StrengthEdit) = viewModel.onStrengthEdit(edit)

    override fun save() = viewModel.save()
}
