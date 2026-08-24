package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.SetType
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.model.exerciseDetailOf
import fr.kristenjestin.mue.domain.model.loadOf
import fr.kristenjestin.mue.domain.model.secondsOf
import fr.kristenjestin.mue.domain.model.strengthSetOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetValidityTest {

    @Test
    fun `a weight and reps set only needs its reps, never a load`() {
        val mode = TrackingMode.WEIGHT_AND_REPS
        assertTrue(mode.isValid(strengthSetOf(reps = 8, loadKg = 60.0)))
        assertTrue(mode.isValid(strengthSetOf(reps = 8)))
        assertFalse(mode.isValid(strengthSetOf(loadKg = 60.0)))
        assertFalse(mode.isValid(strengthSetOf()))
    }

    @Test
    fun `a reps only set needs its reps`() {
        val mode = TrackingMode.REPS_ONLY
        assertTrue(mode.isValid(strengthSetOf(reps = 12)))
        assertFalse(mode.isValid(strengthSetOf(seconds = 60)))
        assertFalse(mode.isValid(strengthSetOf()))
    }

    @Test
    fun `a duration set needs its duration`() {
        val mode = TrackingMode.DURATION
        assertTrue(mode.isValid(strengthSetOf(seconds = 90)))
        assertFalse(mode.isValid(strengthSetOf(reps = 12)))
        assertFalse(mode.isValid(strengthSetOf()))
    }

    @Test
    fun `a weight and duration set only needs its duration, never a load`() {
        val mode = TrackingMode.WEIGHT_AND_DURATION
        assertTrue(mode.isValid(strengthSetOf(seconds = 90, loadKg = 20.0)))
        assertTrue(mode.isValid(strengthSetOf(seconds = 90)))
        assertFalse(mode.isValid(strengthSetOf(loadKg = 20.0)))
    }

    @Test
    fun `a primary measure of zero is as absent as no measure at all`() {
        assertFalse(TrackingMode.REPS_ONLY.isValid(strengthSetOf(reps = 0)))
        assertFalse(TrackingMode.DURATION.isValid(strengthSetOf().copy(duration = secondsOf(0))))
    }

    @Test
    fun `a warm-up is as valid as a working set and PRD 11-2 counts it`() {
        val warmup = strengthSetOf(reps = 10, setType = SetType.WARMUP)
        assertTrue(TrackingMode.WEIGHT_AND_REPS.isValid(warmup))
        assertEquals(
            1,
            StrengthRules.validSetCount(exerciseDetailOf(TrackingMode.WEIGHT_AND_REPS, warmup)),
        )
    }
}

class SetNormalizationTest {

    @Test
    fun `a load typed under weight and reps does not survive a switch to reps only`() {
        val typed = strengthSetOf(reps = 8, loadKg = 60.0)
        val switched = typed.normalizedFor(TrackingMode.REPS_ONLY)
        assertNull(switched.load)
        assertEquals(8, switched.repetitions)
    }

    @Test
    fun `switching between a repetition mode and a duration mode drops the other measure`() {
        val reps = strengthSetOf(reps = 8, loadKg = 60.0)
        val asDuration = reps.normalizedFor(TrackingMode.WEIGHT_AND_DURATION)
        assertNull(asDuration.repetitions)
        assertEquals(loadOf(60.0), asDuration.load)

        val plank = strengthSetOf(seconds = 90)
        assertNull(plank.normalizedFor(TrackingMode.REPS_ONLY).duration)
    }

    @Test
    fun `a per-set effort is kept only by the modes whose rows have room for it`() {
        val effortful = strengthSetOf(reps = 8, seconds = 90, effort = 7)
        assertEquals(7, effortful.normalizedFor(TrackingMode.REPS_ONLY).perceivedEffort?.value)
        assertEquals(7, effortful.normalizedFor(TrackingMode.DURATION).perceivedEffort?.value)
        assertNull(effortful.normalizedFor(TrackingMode.WEIGHT_AND_REPS).perceivedEffort)
        assertNull(effortful.normalizedFor(TrackingMode.WEIGHT_AND_DURATION).perceivedEffort)
    }

    @Test
    fun `normalizing keeps a set's identity, position and type`() {
        val typed = strengthSetOf(position = 3, reps = 8, loadKg = 60.0, setType = SetType.DROP)
        val normalized = typed.normalizedFor(TrackingMode.REPS_ONLY)
        assertEquals(typed.id, normalized.id)
        assertEquals(3, normalized.position)
        assertEquals(SetType.DROP, normalized.setType)
    }
}

class SetCountingTest {

    @Test
    fun `counting a session adds up the valid sets of every exercise`() {
        val exercises = listOf(
            exerciseDetailOf(
                TrackingMode.WEIGHT_AND_REPS,
                strengthSetOf(0, reps = 8, loadKg = 60.0),
                strengthSetOf(1, reps = 8, loadKg = 60.0),
                strengthSetOf(2, loadKg = 60.0),
                name = "Barbell squat",
            ),
            exerciseDetailOf(
                TrackingMode.DURATION,
                strengthSetOf(0, seconds = 90),
                strengthSetOf(1),
                name = "Plank",
            ),
        )
        assertEquals(3, StrengthRules.validSetCount(exercises))
        assertTrue(StrengthRules.hasAnyValidSet(exercises))
    }

    @Test
    fun `a set counted under one mode stops counting under another`() {
        val sets = listOf(strengthSetOf(0, reps = 8), strengthSetOf(1, seconds = 90))
        assertEquals(1, StrengthRules.validSets(TrackingMode.REPS_ONLY, sets).size)
        assertEquals(1, StrengthRules.validSets(TrackingMode.DURATION, sets).size)
    }

    @Test
    fun `an empty session counts nothing and has nothing to save`() {
        assertEquals(0, StrengthRules.validSetCount(emptyList()))
        assertFalse(StrengthRules.hasAnyValidSet(emptyList()))
        assertFalse(
            StrengthRules.hasAnyValidSet(
                listOf(exerciseDetailOf(TrackingMode.REPS_ONLY, strengthSetOf())),
            ),
        )
    }
}

class PersistableExercisesTest {

    @Test
    fun `an exercise left with no valid set is dropped silently`() {
        val exercises = listOf(
            exerciseDetailOf(
                TrackingMode.WEIGHT_AND_REPS,
                strengthSetOf(0, reps = 8),
                name = "Barbell squat",
            ),
            exerciseDetailOf(TrackingMode.DURATION, strengthSetOf(0), name = "Plank"),
        )
        val persistable = StrengthRules.persistableExercises(exercises)
        assertEquals(listOf("Barbell squat"), persistable.map { it.definition.name })
    }

    @Test
    fun `what is written is normalized, so an irrelevant field can never reach the database`() {
        val exercises = listOf(
            exerciseDetailOf(
                TrackingMode.REPS_ONLY,
                strengthSetOf(0, reps = 12, loadKg = 40.0),
                name = "Pull-up",
            ),
        )
        assertNull(StrengthRules.persistableExercises(exercises).single().sets.single().load)
    }

    @Test
    fun `removing an exercise or a set leaves the remaining positions contiguous`() {
        val exercises = listOf(
            exerciseDetailOf(TrackingMode.DURATION, strengthSetOf(0), name = "Plank", position = 0),
            exerciseDetailOf(
                TrackingMode.WEIGHT_AND_REPS,
                strengthSetOf(0),
                strengthSetOf(1, reps = 8),
                strengthSetOf(2),
                strengthSetOf(3, reps = 6),
                name = "Deadlift",
                position = 1,
            ),
        )
        val persistable = StrengthRules.persistableExercises(exercises)
        assertEquals(listOf(0), persistable.map { it.exercise.position })
        assertEquals(listOf(0, 1), persistable.single().sets.map { it.position })
        assertEquals(listOf(8, 6), persistable.single().sets.map { it.repetitions })
    }
}

class LastValidSetTest {

    @Test
    fun `the last performance reads the last valid set, whatever comes after it`() {
        val sets = listOf(
            strengthSetOf(0, reps = 10, loadKg = 55.0),
            strengthSetOf(1, reps = 8, loadKg = 60.0),
            strengthSetOf(2, loadKg = 60.0),
        )
        val last = StrengthRules.lastValidSet(TrackingMode.WEIGHT_AND_REPS, sets)
        assertEquals(8, last?.repetitions)
        assertEquals(loadOf(60.0), last?.load)
    }

    @Test
    fun `sets out of order are read by position, not by the order they arrive in`() {
        val sets = listOf(strengthSetOf(2, reps = 6), strengthSetOf(0, reps = 10))
        assertEquals(6, StrengthRules.lastValidSet(TrackingMode.REPS_ONLY, sets)?.repetitions)
    }

    @Test
    fun `an exercise that was never really performed shows nothing`() {
        assertNull(StrengthRules.lastValidSet(TrackingMode.REPS_ONLY, emptyList()))
        assertNull(
            StrengthRules.lastValidSet(TrackingMode.REPS_ONLY, listOf(strengthSetOf(0))),
        )
    }

    @Test
    fun `a duration performance carries the minutes and seconds PRD 11-4 shows`() {
        val last = StrengthRules.lastValidSet(
            TrackingMode.WEIGHT_AND_DURATION,
            listOf(strengthSetOf(0, seconds = 90, loadKg = 20.0)),
        )
        assertEquals(1, last?.duration?.totalMinutes)
        assertEquals(30, last?.duration?.secondsPart)
        assertEquals(20.0, last?.load?.kilograms)
    }
}
