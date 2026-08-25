package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.logic.StrengthRules
import fr.kristenjestin.mue.domain.logic.normalizedFor
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import fr.kristenjestin.mue.domain.model.SetType
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode

/** The cell of a set row an edit lands on; one per column [MueSetRow] can draw. */
enum class StrengthSetField { LOAD, REPETITIONS, DURATION, EFFORT }

/**
 * Every mutation the strength editor can ask for, as a value.
 *
 * `Log activity` and `Strength session` edit the **same** draft (PRD 9.1), and the draft lives
 * in the log form's ViewModel. Rather than hand the editor a ViewModel it would then be coupled
 * to, the whole screen speaks in these, and the ViewModel spends a single line on them:
 *
 * ```
 * fun onStrengthEdit(edit: StrengthEdit) { updateDraft { StrengthDraftEditor.apply(it, edit) } }
 * ```
 *
 * The session trio at the top of the editor — duration, effort, estimated energy — is modelled
 * here too, and writes the very fields the log form's own inputs write. Routing it through the
 * same seam keeps the editor to one outgoing call instead of five, and cannot diverge from the
 * form: both end up in `ActivityDraft.hours`, `.minutes`, `.perceivedEffort` and the active
 * preset's `estimated_energy` input.
 *
 * An exercise and a set are addressed by their position in the draft, because neither
 * `ExerciseDraft` nor `SetDraft` carries an identifier — the draft is what was typed, not what
 * was stored, and identifiers are minted on the way to the database.
 */
@Immutable
sealed interface StrengthEdit {

    // region The session trio, shared with the log form

    data class SetDurationHours(val raw: String) : StrengthEdit

    data class SetDurationMinutes(val raw: String) : StrengthEdit

    /** Null clears it: perceived effort is optional in both modes of PRD 9.1. */
    data class SetSessionEffort(val effort: Int?) : StrengthEdit

    data class SetEstimatedEnergy(val raw: String) : StrengthEdit

    // endregion

    // region Exercises

    /** PRD FR-ACTIVITY-009: an exercise arrives with one set, suited to its tracking mode. */
    data class AddExercise(val definition: ExerciseDefinition) : StrengthEdit

    data class RemoveExercise(val exercise: Int) : StrengthEdit

    /** Contract decision 4: reordering is two buttons, never a drag. */
    data class MoveExerciseUp(val exercise: Int) : StrengthEdit

    data class MoveExerciseDown(val exercise: Int) : StrengthEdit

    data class SetExerciseNotes(val exercise: Int, val notes: String) : StrengthEdit

    /** PRD 9.4: the sets are normalised on the way, so no irrelevant value survives. */
    data class SetTrackingMode(val exercise: Int, val mode: TrackingMode) : StrengthEdit

    // endregion

    // region Sets

    /** PRD 9.4: entirely empty, never seeded with a plausible-looking zero. */
    data class AddSet(val exercise: Int) : StrengthEdit

    data class DuplicateLastSet(val exercise: Int) : StrengthEdit

    data class RemoveSet(val exercise: Int, val set: Int) : StrengthEdit

    data class EditSet(
        val exercise: Int,
        val set: Int,
        val field: StrengthSetField,
        val value: String,
    ) : StrengthEdit

    // endregion
}

/**
 * The strength half of the draft, as pure `ActivityDraft -> ActivityDraft` transformations.
 *
 * Nothing here is a composable, a ViewModel or a coroutine: the whole editor is a function of
 * its draft, so every rule below is provable without a device.
 *
 * Two rules run through the file. **Every index is tolerated**: an edit naming a row that is no
 * longer there returns the draft untouched, because a tap and a recomposition race and a stale
 * position must not crash a form someone is filling in. And **the tracking-mode rule is never
 * restated** — `StrengthRules` and `normalizedFor` are the only place it lives, so a set that is
 * dropped here is dropped for exactly the reason the database would drop it.
 */
object StrengthDraftEditor {

    /**
     * Keeps a numeric cell readable. `125.5` and `12:30` are five glyphs; eight leaves room for
     * a typing accident without letting one column push the other off the row.
     */
    const val MAX_SET_INPUT_LENGTH: Int = 8

    /** 99 h 59 m is the longest session PRD FR-ACTIVITY-005 accepts, so two digits each. */
    private const val MAX_DURATION_PART_LENGTH = 2

    /** Far above any plausible session, and short enough that the box never scrolls. */
    private const val MAX_ENERGY_LENGTH = 5

    fun apply(draft: ActivityDraft, edit: StrengthEdit): ActivityDraft = when (edit) {
        is StrengthEdit.SetDurationHours ->
            draft.copy(hours = edit.raw.digits(MAX_DURATION_PART_LENGTH))

        is StrengthEdit.SetDurationMinutes ->
            draft.copy(minutes = edit.raw.digits(MAX_DURATION_PART_LENGTH))

        is StrengthEdit.SetSessionEffort ->
            draft.copy(perceivedEffort = edit.effort?.takeIf { it in PerceivedEffort.RANGE })

        is StrengthEdit.SetEstimatedEnergy -> draft.withPresetDraft {
            it.withMetric(MetricKind.ESTIMATED_ENERGY, edit.raw.digits(MAX_ENERGY_LENGTH))
        }

        is StrengthEdit.AddExercise -> draft.copy(
            exercises = draft.exercises + edit.definition.toDraft(),
        )

        is StrengthEdit.RemoveExercise -> draft.withExercises(edit.exercise) { exercises ->
            exercises.filterIndexed { index, _ -> index != edit.exercise }
        }

        is StrengthEdit.MoveExerciseUp -> draft.swapExercises(edit.exercise, edit.exercise - 1)

        is StrengthEdit.MoveExerciseDown -> draft.swapExercises(edit.exercise, edit.exercise + 1)

        is StrengthEdit.SetExerciseNotes -> draft.withExercise(edit.exercise) {
            it.copy(notes = edit.notes.take(MAX_EXERCISE_NOTES_LENGTH))
        }

        is StrengthEdit.SetTrackingMode -> draft.withExercise(edit.exercise) { exercise ->
            exercise.copy(
                trackingModeId = edit.mode.id,
                sets = exercise.sets.map { it.normalizedFor(edit.mode) },
            )
        }

        is StrengthEdit.AddSet -> draft.withExercise(edit.exercise) {
            it.copy(sets = it.sets + SetDraft())
        }

        is StrengthEdit.DuplicateLastSet -> draft.withExercise(edit.exercise) { exercise ->
            val last = exercise.sets.lastOrNull() ?: return@withExercise exercise
            exercise.copy(sets = exercise.sets + last.copy())
        }

        is StrengthEdit.RemoveSet -> draft.withExercise(edit.exercise) { exercise ->
            if (edit.set !in exercise.sets.indices) return@withExercise exercise
            exercise.copy(sets = exercise.sets.filterIndexed { index, _ -> index != edit.set })
        }

        is StrengthEdit.EditSet -> draft.withExercise(edit.exercise) { exercise ->
            if (edit.set !in exercise.sets.indices) return@withExercise exercise
            exercise.copy(
                sets = exercise.sets.mapIndexed { index, set ->
                    if (index == edit.set) set.edited(edit.field, edit.value) else set
                },
            )
        }
    }

    /**
     * The definition an `Add exercise` should carry, given what the person typed.
     *
     * PRD 9.2: a name already in the catalogue — whatever its case or padding — reuses that
     * definition instead of creating a second one, and the mode chosen here therefore only
     * applies to a definition that is really new. A name already sitting in *this* draft is
     * folded the same way, so typing `zercher squat` twice in one session references one
     * definition rather than minting a second unsaved one.
     *
     * Returns null when the name is not one PRD 9.2 accepts, so the caller has nothing to add.
     */
    fun definitionFor(
        name: String,
        mode: TrackingMode,
        catalogue: List<ExerciseDefinition>,
        draft: ActivityDraft = ActivityDraft(),
        id: ExerciseDefinitionId = ExerciseDefinitionId.random(),
    ): ExerciseDefinition? {
        val validName = ActivityValidation.validateExerciseName(name).valueOrNull ?: return null
        val folded = ExerciseDefinition.fold(validName)
        catalogue.firstOrNull { it.nameFolded == folded }?.let { return it }
        draft.exercises
            .firstOrNull { ExerciseDefinition.fold(it.name) == folded }
            ?.let { return it.toDefinition() }
        return ExerciseDefinition(
            id = id,
            name = validName,
            trackingMode = mode,
            equipment = null,
            isCustom = true,
        )
    }

    /**
     * What the save path writes (PRD 9.4, FR-ACTIVITY-009).
     *
     * The invariant is enforced on the way out and nowhere else: `StrengthRules` keeps only the
     * valid sets, renumbers them, and drops an exercise left with none — silently, as
     * FR-ACTIVITY-009 requires. A field the mode does not expose never reaches this point, and a
     * field that does not parse is simply absent, which is what PRD 12 means by "an empty
     * optional value is null, never zero".
     *
     * The identifiers are minted here because a draft has none; they are parameters so a test
     * can pin them.
     */
    fun persistableExercises(
        draft: ActivityDraft,
        newExerciseId: () -> StrengthExerciseId = StrengthExerciseId::random,
        newSetId: () -> StrengthSetId = StrengthSetId::random,
    ): List<StrengthExerciseDetail> = StrengthRules.persistableExercises(
        draft.exercises.mapIndexed { index, exercise ->
            exercise.toDetail(index, newExerciseId(), newSetId)
        },
    )

    /** PRD 11.2: the count the header shows, warm-ups included. */
    fun validSetCount(draft: ActivityDraft): Int =
        StrengthRules.validSetCount(draft.exercises.map { it.toDetail(0, PROBE_EXERCISE_ID) { PROBE_SET_ID } })

    /** PRD FR-ACTIVITY-009: a detailed session needs one valid set before it can be saved. */
    fun hasAnyValidSet(draft: ActivityDraft): Boolean = validSetCount(draft) > 0

    /** PRD 9.3: a note is a comment on one exercise, not an essay. */
    const val MAX_EXERCISE_NOTES_LENGTH: Int = 500

    // region Draft plumbing

    private fun String.digits(max: Int): String = filter { it.isDigit() }.take(max)

    private fun ActivityDraft.withExercises(
        index: Int,
        block: (List<ExerciseDraft>) -> List<ExerciseDraft>,
    ): ActivityDraft =
        if (index in exercises.indices) copy(exercises = block(exercises)) else this

    private fun ActivityDraft.withExercise(
        index: Int,
        block: (ExerciseDraft) -> ExerciseDraft,
    ): ActivityDraft = withExercises(index) { exercises ->
        exercises.mapIndexed { position, exercise ->
            if (position == index) block(exercise) else exercise
        }
    }

    /** Both ends of the list are a no-op rather than an error: the buttons are simply disabled. */
    private fun ActivityDraft.swapExercises(from: Int, to: Int): ActivityDraft {
        if (from !in exercises.indices || to !in exercises.indices) return this
        val moved = exercises.toMutableList()
        moved[from] = exercises[to]
        moved[to] = exercises[from]
        return copy(exercises = moved)
    }

    private fun ExerciseDefinition.toDraft(): ExerciseDraft = ExerciseDraft(
        definitionId = id.value,
        name = name,
        trackingModeId = trackingMode.id,
        equipmentId = equipment?.id,
        isCustom = isCustom,
        // FR-ACTIVITY-009: the exercise arrives with a first set, and PRD 9.4 starts it empty.
        sets = listOf(SetDraft()),
    )

    private fun SetDraft.edited(field: StrengthSetField, value: String): SetDraft {
        val raw = value.take(MAX_SET_INPUT_LENGTH)
        return when (field) {
            StrengthSetField.LOAD -> copy(loadKg = raw)
            StrengthSetField.REPETITIONS -> copy(reps = raw)
            StrengthSetField.DURATION -> copy(durationSeconds = raw)
            /*
             * Effort is the one cell whose draft field is a number rather than the raw text
             * (`SetDraft` is frozen), so it cannot hold a half-typed value. Blank clears it,
             * a value on PRD 8.2's scale sets it, and anything else — a `0`, a third digit —
             * leaves the cell as it was rather than showing a value the person did not choose.
             */
            StrengthSetField.EFFORT -> when {
                raw.isBlank() -> copy(perceivedEffort = null)
                else -> ActivityValidation.parseInteger(raw)
                    ?.takeIf { it in PerceivedEffort.RANGE }
                    ?.let { copy(perceivedEffort = it) }
                    ?: this
            }
        }
    }

    /**
     * Drops what the new mode does not expose, without restating which fields those are.
     *
     * `normalizedFor` is the one place the rule lives, but it speaks in parsed values while a
     * draft holds text. Running a set that carries *every* field through it says which fields
     * the mode keeps, and the raw text of the survivors is preserved exactly as typed — a
     * half-typed `7,` outlives a mode change the way PRD 16.4 says it outlives process death.
     */
    private fun SetDraft.normalizedFor(mode: TrackingMode): SetDraft {
        val kept = ProbeSet.normalizedFor(mode)
        return copy(
            reps = if (kept.repetitions != null) reps else "",
            loadKg = if (kept.load != null) loadKg else "",
            durationSeconds = if (kept.duration != null) durationSeconds else "",
            perceivedEffort = perceivedEffort.takeIf { kept.perceivedEffort != null },
        )
    }

    private fun ExerciseDraft.toDefinition(): ExerciseDefinition = ExerciseDefinition(
        id = ExerciseDefinitionId(definitionId),
        name = name,
        trackingMode = TrackingMode.fromId(trackingModeId),
        equipment = equipmentId?.let(EquipmentType::fromId),
        isCustom = isCustom,
    )

    private fun ExerciseDraft.toDetail(
        position: Int,
        exerciseId: StrengthExerciseId,
        newSetId: () -> StrengthSetId,
    ): StrengthExerciseDetail = StrengthExerciseDetail(
        exercise = StrengthExercise(
            id = exerciseId,
            position = position,
            notes = ActivityValidation.normalizeNotes(notes),
        ),
        definition = toDefinition(),
        sets = sets.mapIndexed { index, set -> set.toDomain(index, newSetId()) },
    )

    private fun SetDraft.toDomain(position: Int, id: StrengthSetId): StrengthSet = StrengthSet(
        id = id,
        position = position,
        setType = SetType.fromId(setTypeId),
        repetitions = ActivityValidation.validateRepetitions(reps).valueOrNull,
        load = ActivityValidation.validateLoad(loadKg).valueOrNull,
        duration = ActivityValidation.validateSetDuration(durationSeconds).valueOrNull,
        perceivedEffort = perceivedEffort?.let { PerceivedEffort.ofOrNull(it) },
    )

    /**
     * A set carrying every measure at once, used only to ask `normalizedFor` which of them a
     * mode keeps. It is never stored and never shown.
     */
    private val ProbeSet = StrengthSet(
        id = StrengthSetId("probe"),
        position = 0,
        repetitions = StrengthSet.REPETITIONS_RANGE.first,
        load = Load.ofGramsOrNull(Load.STEP_GRAMS),
        duration = ActivityDuration.ofSecondsOrNull(1),
        perceivedEffort = PerceivedEffort.ofOrNull(PerceivedEffort.MIN),
    )

    /** Counting sets never leaves memory, so the identifiers only have to exist. */
    private val PROBE_EXERCISE_ID = StrengthExerciseId("counted")
    private val PROBE_SET_ID = StrengthSetId("counted")

    // endregion
}
