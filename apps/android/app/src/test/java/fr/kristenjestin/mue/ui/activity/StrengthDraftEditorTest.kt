package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The strength half of the draft, which is a pure function of its input and is therefore the
 * one part of `Strength session` that can be proven without a device.
 *
 * Everything the editor claims about PRD 9.4 — the empty set, the normalisation on a mode
 * change, the silent drop at save — is asserted here rather than through the screen.
 */
class StrengthDraftEditorTest {

    private val squat = ExerciseDefinition(
        id = ExerciseDefinitionId("squat"),
        name = "Barbell squat",
        trackingMode = TrackingMode.WEIGHT_AND_REPS,
        equipment = EquipmentType.BARBELL,
    )

    private val plank = ExerciseDefinition(
        id = ExerciseDefinitionId("plank"),
        name = "Plank",
        trackingMode = TrackingMode.DURATION,
        equipment = EquipmentType.BODYWEIGHT,
    )

    private val bench = ExerciseDefinition(
        id = ExerciseDefinitionId("bench"),
        name = "Bench press",
        trackingMode = TrackingMode.WEIGHT_AND_REPS,
        equipment = EquipmentType.BARBELL,
    )

    private val pullUp = ExerciseDefinition(
        id = ExerciseDefinitionId("pull-up"),
        name = "Pull-up",
        trackingMode = TrackingMode.REPS_ONLY,
        equipment = EquipmentType.BODYWEIGHT,
    )

    private fun draftOf(vararg definitions: ExerciseDefinition): ActivityDraft =
        definitions.fold(ActivityDraft(detailed = true)) { draft, definition ->
            StrengthDraftEditor.apply(draft, StrengthEdit.AddExercise(definition))
        }

    private fun ActivityDraft.edit(vararg edits: StrengthEdit): ActivityDraft =
        edits.fold(this, StrengthDraftEditor::apply)

    // region The session trio

    @Test
    fun `the duration boxes keep two digits of what was typed`() {
        val draft = ActivityDraft().edit(
            StrengthEdit.SetDurationHours("1"),
            StrengthEdit.SetDurationMinutes("05"),
        )

        assertEquals("1", draft.hours)
        assertEquals("05", draft.minutes)
    }

    @Test
    fun `a duration box refuses anything that is not a number`() {
        val draft = ActivityDraft().edit(
            StrengthEdit.SetDurationHours("1h2"),
            StrengthEdit.SetDurationMinutes("999"),
        )

        assertEquals("12", draft.hours)
        assertEquals("99", draft.minutes)
    }

    @Test
    fun `session effort takes the scale of PRD 8 2 and nothing else`() {
        assertEquals(7, ActivityDraft().edit(StrengthEdit.SetSessionEffort(7)).perceivedEffort)
        assertNull(ActivityDraft().edit(StrengthEdit.SetSessionEffort(0)).perceivedEffort)
        assertNull(ActivityDraft().edit(StrengthEdit.SetSessionEffort(11)).perceivedEffort)
        assertNull(ActivityDraft().edit(StrengthEdit.SetSessionEffort(null)).perceivedEffort)
    }

    /** PRD 9.1: the trio writes the very fields the log form writes, per active preset. */
    @Test
    fun `estimated energy lands on the active preset's own metric input`() {
        val draft = ActivityDraft(presetId = ActivityPreset.STRENGTH_TRAINING.id)
            .edit(StrengthEdit.SetEstimatedEnergy("320"))

        assertEquals("320", draft.presetDraft().metricInput(MetricKind.ESTIMATED_ENERGY))
        assertEquals(
            "",
            draft.presetDraft(ActivityPreset.RUN).metricInput(MetricKind.ESTIMATED_ENERGY),
        )
    }

    // endregion

    // region Exercises

    @Test
    fun `an added exercise arrives with one set and that set is entirely empty`() {
        val draft = draftOf(squat)

        val exercise = draft.exercises.single()
        assertEquals("Barbell squat", exercise.name)
        assertEquals(TrackingMode.WEIGHT_AND_REPS.id, exercise.trackingModeId)
        assertEquals(EquipmentType.BARBELL.id, exercise.equipmentId)
        assertEquals(SetDraft(), exercise.sets.single())
    }

    @Test
    fun `removing an exercise leaves the others in place`() {
        val draft = draftOf(squat, plank, pullUp).edit(StrengthEdit.RemoveExercise(1))

        assertEquals(listOf("Barbell squat", "Pull-up"), draft.exercises.map { it.name })
    }

    @Test
    fun `moving down swaps an exercise with the one below it`() {
        val draft = draftOf(squat, plank, pullUp).edit(StrengthEdit.MoveExerciseDown(0))

        assertEquals(listOf("Plank", "Barbell squat", "Pull-up"), draft.exercises.map { it.name })
    }

    @Test
    fun `moving up swaps an exercise with the one above it`() {
        val draft = draftOf(squat, plank, pullUp).edit(StrengthEdit.MoveExerciseUp(2))

        assertEquals(listOf("Barbell squat", "Pull-up", "Plank"), draft.exercises.map { it.name })
    }

    /** Contract decision 4 gives both ends a button; neither of them may lose an exercise. */
    @Test
    fun `moving past either end of the list changes nothing`() {
        val draft = draftOf(squat, plank)

        assertEquals(draft, draft.edit(StrengthEdit.MoveExerciseUp(0)))
        assertEquals(draft, draft.edit(StrengthEdit.MoveExerciseDown(1)))
    }

    @Test
    fun `an exercise note is kept as typed and capped`() {
        val long = "x".repeat(StrengthDraftEditor.MAX_EXERCISE_NOTES_LENGTH + 40)
        val draft = draftOf(squat).edit(
            StrengthEdit.SetExerciseNotes(0, "Left knee felt off"),
        )

        assertEquals("Left knee felt off", draft.exercises.single().notes)
        assertEquals(
            StrengthDraftEditor.MAX_EXERCISE_NOTES_LENGTH,
            draftOf(squat).edit(StrengthEdit.SetExerciseNotes(0, long))
                .exercises.single().notes.length,
        )
    }

    // endregion

    // region Sets

    @Test
    fun `an added set is empty, never seeded the way the prototype seeds it`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "60"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.AddSet(0),
        )

        assertEquals(2, draft.exercises.single().sets.size)
        assertEquals(SetDraft(), draft.exercises.single().sets.last())
    }

    @Test
    fun `duplicating repeats every value of the set immediately before it`() {
        val draft = draftOf(pullUp).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "12"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.EFFORT, "8"),
            StrengthEdit.DuplicateLastSet(0),
        )

        val sets = draft.exercises.single().sets
        assertEquals(2, sets.size)
        assertEquals(sets.first(), sets.last())
        assertEquals("12", sets.last().reps)
        assertEquals(8, sets.last().perceivedEffort)
    }

    @Test
    fun `duplicating copies the load as well as the repetitions`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "62.5"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.DuplicateLastSet(0),
        )

        assertEquals("62.5", draft.exercises.single().sets.last().loadKg)
        assertEquals("8", draft.exercises.single().sets.last().reps)
    }

    @Test
    fun `there is nothing to duplicate once the last set is gone`() {
        val emptied = draftOf(squat).edit(StrengthEdit.RemoveSet(0, 0))

        assertTrue(emptied.exercises.single().sets.isEmpty())
        assertEquals(emptied, emptied.edit(StrengthEdit.DuplicateLastSet(0)))
    }

    @Test
    fun `removing the last set leaves the exercise in the draft`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.RemoveSet(0, 0),
        )

        assertEquals(1, draft.exercises.size)
        assertTrue(draft.exercises.single().sets.isEmpty())
    }

    @Test
    fun `a set cell keeps the text exactly as it was typed`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "62,"),
        )

        assertEquals("62,", draft.exercises.single().sets.single().loadKg)
    }

    @Test
    fun `a set cell stops at the width its column can read`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "1234567890"),
        )

        assertEquals(
            StrengthDraftEditor.MAX_SET_INPUT_LENGTH,
            draft.exercises.single().sets.single().loadKg.length,
        )
    }

    @Test
    fun `per-set effort clears on a blank and refuses a value off the scale`() {
        val withEffort = draftOf(pullUp).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.EFFORT, "9"),
        )
        assertEquals(9, withEffort.exercises.single().sets.single().perceivedEffort)

        val refused = withEffort.edit(StrengthEdit.EditSet(0, 0, StrengthSetField.EFFORT, "0"))
        assertEquals(9, refused.exercises.single().sets.single().perceivedEffort)

        val cleared = withEffort.edit(StrengthEdit.EditSet(0, 0, StrengthSetField.EFFORT, ""))
        assertNull(cleared.exercises.single().sets.single().perceivedEffort)
    }

    // endregion

    // region Tracking mode

    /** PRD 9.4: a load typed under `weight_and_reps` cannot survive a switch to `reps_only`. */
    @Test
    fun `switching to reps only drops the load and keeps the repetitions`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "60"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.SetTrackingMode(0, TrackingMode.REPS_ONLY),
        )

        val set = draft.exercises.single().sets.single()
        assertEquals(TrackingMode.REPS_ONLY.id, draft.exercises.single().trackingModeId)
        assertEquals("", set.loadKg)
        assertEquals("8", set.reps)
    }

    @Test
    fun `switching to duration drops the repetitions and keeps the load`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "20"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.SetTrackingMode(0, TrackingMode.WEIGHT_AND_DURATION),
        )

        val set = draft.exercises.single().sets.single()
        assertEquals("20", set.loadKg)
        assertEquals("", set.reps)
    }

    /** Contract decision 3: the effort column only exists where the row leaves one free. */
    @Test
    fun `switching to a mode with a load drops the per-set effort`() {
        val draft = draftOf(pullUp).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "12"),
            StrengthEdit.EditSet(0, 0, StrengthSetField.EFFORT, "8"),
            StrengthEdit.SetTrackingMode(0, TrackingMode.WEIGHT_AND_REPS),
        )

        assertNull(draft.exercises.single().sets.single().perceivedEffort)
        assertEquals("12", draft.exercises.single().sets.single().reps)
    }

    @Test
    fun `switching normalises every set of the exercise, not only the first`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "60"),
            StrengthEdit.AddSet(0),
            StrengthEdit.EditSet(0, 1, StrengthSetField.LOAD, "70"),
            StrengthEdit.SetTrackingMode(0, TrackingMode.REPS_ONLY),
        )

        assertTrue(draft.exercises.single().sets.all { it.loadKg.isEmpty() })
    }

    @Test
    fun `a half-typed value outlives a mode that keeps its column`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.SetTrackingMode(0, TrackingMode.REPS_ONLY),
        )

        assertEquals("8", draft.exercises.single().sets.single().reps)
    }

    // endregion

    // region Stale indices

    @Test
    fun `an edit naming a row that is gone leaves the draft alone`() {
        val draft = draftOf(squat)

        assertSame(draft, StrengthDraftEditor.apply(draft, StrengthEdit.RemoveExercise(4)))
        assertSame(draft, StrengthDraftEditor.apply(draft, StrengthEdit.AddSet(-1)))
        assertEquals(draft, draft.edit(StrengthEdit.RemoveSet(0, 9)))
        assertEquals(
            draft,
            draft.edit(StrengthEdit.EditSet(0, 9, StrengthSetField.REPETITIONS, "8")),
        )
    }

    // endregion

    // region The catalogue fold

    /** PRD 9.2: case and padding never create a second definition. */
    @Test
    fun `a name already in the catalogue reuses its definition whatever its case`() {
        val found = StrengthDraftEditor.definitionFor(
            name = "  bench PRESS ",
            mode = TrackingMode.DURATION,
            catalogue = listOf(squat, bench),
        )

        assertSame(bench, found)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, found?.trackingMode)
    }

    @Test
    fun `a name new to the catalogue becomes a custom definition in the chosen mode`() {
        val created = StrengthDraftEditor.definitionFor(
            name = " Zercher squat ",
            mode = TrackingMode.REPS_ONLY,
            catalogue = listOf(squat),
            id = ExerciseDefinitionId("new"),
        )

        assertNotNull(created)
        assertEquals("Zercher squat", created.name)
        assertEquals(TrackingMode.REPS_ONLY, created.trackingMode)
        assertTrue(created.isCustom)
        assertEquals("new", created.id.value)
    }

    @Test
    fun `the same custom name typed twice in one session points at one definition`() {
        val first = StrengthDraftEditor.definitionFor(
            name = "Zercher squat",
            mode = TrackingMode.REPS_ONLY,
            catalogue = emptyList(),
            id = ExerciseDefinitionId("first"),
        )
        val draft = draftOf().edit(StrengthEdit.AddExercise(requireNotNull(first)))

        val second = StrengthDraftEditor.definitionFor(
            name = "zercher SQUAT",
            mode = TrackingMode.DURATION,
            catalogue = emptyList(),
            draft = draft,
            id = ExerciseDefinitionId("second"),
        )

        assertEquals(first.id, second?.id)
        assertEquals(TrackingMode.REPS_ONLY, second?.trackingMode)
    }

    @Test
    fun `a name PRD 9 2 refuses yields nothing to add`() {
        assertNull(
            StrengthDraftEditor.definitionFor("   ", TrackingMode.REPS_ONLY, emptyList()),
        )
        assertNull(
            StrengthDraftEditor.definitionFor(
                name = "x".repeat(ExerciseDefinition.MAX_NAME_LENGTH + 1),
                mode = TrackingMode.REPS_ONLY,
                catalogue = emptyList(),
            ),
        )
    }

    // endregion

    // region The way out

    @Test
    fun `only the sets that carry their primary measure are persisted`() {
        val draft = draftOf(squat).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.LOAD, "60"),
            StrengthEdit.AddSet(0),
            StrengthEdit.EditSet(0, 1, StrengthSetField.LOAD, "60"),
            StrengthEdit.EditSet(0, 1, StrengthSetField.REPETITIONS, "8"),
        )

        val exercises = StrengthDraftEditor.persistableExercises(draft)
        val sets = exercises.single().sets
        assertEquals(1, sets.size)
        assertEquals(8, sets.single().repetitions)
        assertEquals(60_000, sets.single().load?.grams)
        assertEquals(0, sets.single().position)
    }

    /** PRD FR-ACTIVITY-009: an exercise with no valid set goes, and says nothing about it. */
    @Test
    fun `an exercise left with no valid set is dropped at save`() {
        val draft = draftOf(squat, plank, pullUp).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.EditSet(1, 0, StrengthSetField.LOAD, "20"),
            StrengthEdit.EditSet(2, 0, StrengthSetField.REPETITIONS, "12"),
        )

        val exercises = StrengthDraftEditor.persistableExercises(draft)

        assertEquals(listOf("Barbell squat", "Pull-up"), exercises.map { it.definition.name })
        assertEquals(listOf(0, 1), exercises.map { it.exercise.position })
    }

    @Test
    fun `an empty draft persists nothing at all`() {
        assertTrue(StrengthDraftEditor.persistableExercises(draftOf(squat, plank)).isEmpty())
        assertFalse(StrengthDraftEditor.hasAnyValidSet(draftOf(squat)))
        assertEquals(0, StrengthDraftEditor.validSetCount(draftOf(squat)))
    }

    @Test
    fun `the header count follows the valid sets of every exercise`() {
        val draft = draftOf(squat, plank).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.REPETITIONS, "8"),
            StrengthEdit.DuplicateLastSet(0),
            StrengthEdit.EditSet(1, 0, StrengthSetField.DURATION, "1:30"),
        )

        assertEquals(3, StrengthDraftEditor.validSetCount(draft))
        assertTrue(StrengthDraftEditor.hasAnyValidSet(draft))
    }

    @Test
    fun `the notes and the tracking mode travel with what is persisted`() {
        val draft = draftOf(plank).edit(
            StrengthEdit.SetExerciseNotes(0, "  Elbows under the shoulders  "),
            StrengthEdit.EditSet(0, 0, StrengthSetField.DURATION, "90"),
        )

        val detail = StrengthDraftEditor.persistableExercises(
            draft,
            newExerciseId = { StrengthExerciseId("exercise") },
            newSetId = { StrengthSetId("set") },
        ).single()

        assertEquals("Elbows under the shoulders", detail.exercise.notes)
        assertEquals(TrackingMode.DURATION, detail.definition.trackingMode)
        assertEquals("exercise", detail.exercise.id.value)
        assertEquals(90, detail.sets.single().duration?.seconds)
    }

    @Test
    fun `a set duration is read either as seconds or as minutes and seconds`() {
        val draft = draftOf(plank).edit(
            StrengthEdit.EditSet(0, 0, StrengthSetField.DURATION, "1:30"),
        )

        assertEquals(90, StrengthDraftEditor.persistableExercises(draft).single().sets.single().duration?.seconds)
    }

    // endregion
}
