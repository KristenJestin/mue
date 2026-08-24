package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.SetMeasure
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.TrackingMode

/*
 * The set rules of PRD 9.4, in the one place every caller goes through.
 *
 * The invariant, stated here once: a set that is not valid is never written, and neither is an
 * exercise left with no valid set. PRD 9.4 and FR-ACTIVITY-009 both demand it at write time, so
 * `StrengthRules.persistableExercises` is what the save path calls and nothing downstream ever
 * re-expresses the rule — counting the sets of a stored session is a plain `COUNT(*)`.
 */

/**
 * A set is valid when it carries the primary measure of its mode (PRD 9.4). The load is always
 * optional: an empty bar, a band and a pull-up are all real sets with no weight at all.
 */
fun TrackingMode.isValid(set: StrengthSet): Boolean = when (primary) {
    SetMeasure.REPETITIONS -> set.repetitions != null && set.repetitions > 0
    SetMeasure.DURATION -> set.duration != null && set.duration.seconds > 0
}

/**
 * Drops every field the mode does not expose, so a load typed under `weight_and_reps` cannot
 * survive a switch to `reps_only` (PRD 9.4: a set keeps no irrelevant value).
 */
fun StrengthSet.normalizedFor(mode: TrackingMode): StrengthSet = copy(
    repetitions = repetitions.takeIf { mode.primary == SetMeasure.REPETITIONS },
    load = load.takeIf { mode.usesLoad },
    duration = duration.takeIf { mode.primary == SetMeasure.DURATION },
    perceivedEffort = perceivedEffort.takeIf { mode.showsSetEffort },
)

object StrengthRules {

    /** Normalises first, so a set is judged on the fields its mode actually keeps. */
    fun validSets(mode: TrackingMode, sets: List<StrengthSet>): List<StrengthSet> =
        sets.map { it.normalizedFor(mode) }.filter { mode.isValid(it) }

    fun validSetCount(exercise: StrengthExerciseDetail): Int =
        validSets(exercise.definition.trackingMode, exercise.sets).size

    /** PRD 11.2: every exercise of the session counts, warm-ups included. */
    fun validSetCount(exercises: List<StrengthExerciseDetail>): Int =
        exercises.sumOf { validSetCount(it) }

    /**
     * What the save path writes: each exercise keeps only its valid sets, renumbered from zero,
     * and an exercise left with none is removed silently (PRD FR-ACTIVITY-009).
     */
    fun persistableExercises(
        exercises: List<StrengthExerciseDetail>,
    ): List<StrengthExerciseDetail> = exercises
        .map { detail ->
            val sets = validSets(detail.definition.trackingMode, detail.sets)
                .mapIndexed { index, set -> set.copy(position = index) }
            detail.copy(sets = sets)
        }
        .filter { it.sets.isNotEmpty() }
        .mapIndexed { index, detail ->
            detail.copy(exercise = detail.exercise.copy(position = index))
        }

    /** PRD FR-ACTIVITY-009: a detailed session needs at least one valid set to be saved. */
    fun hasAnyValidSet(exercises: List<StrengthExerciseDetail>): Boolean =
        exercises.any { validSetCount(it) > 0 }

    /** The set PRD 11.4 reads a last performance from: the last valid one, by position. */
    fun lastValidSet(mode: TrackingMode, sets: List<StrengthSet>): StrengthSet? =
        validSets(mode, sets.sortedBy { it.position }).lastOrNull()
}
