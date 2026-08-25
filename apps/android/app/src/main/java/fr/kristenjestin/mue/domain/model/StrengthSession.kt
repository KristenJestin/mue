package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.util.Locale

/** The quantity a tracking mode is built around (PRD 9.4). The load is never one of them. */
enum class SetMeasure { REPETITIONS, DURATION }

/**
 * What a set of an exercise records (PRD 9.2).
 *
 * [primary] is the measure a set must carry to be valid; [usesLoad] says whether a load field
 * is offered at all, and a load is optional wherever it is offered — an empty bar, a band and
 * a pull-up are all logged without one (PRD 9.4).
 *
 * [showsSetEffort] is a layout rule rather than a data rule: a set row has room for a third
 * column only when the mode leaves one free, and the session already carries an effort of its own.
 */
enum class TrackingMode(
    val id: String,
    val label: String,
    val primary: SetMeasure,
    val usesLoad: Boolean,
) {
    WEIGHT_AND_REPS("weight_and_reps", "Weight & reps", SetMeasure.REPETITIONS, usesLoad = true),
    REPS_ONLY("reps_only", "Reps only", SetMeasure.REPETITIONS, usesLoad = false),
    DURATION("duration", "Duration", SetMeasure.DURATION, usesLoad = false),
    WEIGHT_AND_DURATION(
        "weight_and_duration",
        "Weight & duration",
        SetMeasure.DURATION,
        usesLoad = true,
    ),
    ;

    val showsSetEffort: Boolean get() = !usesLoad

    companion object {
        private val byId: Map<String, TrackingMode> = entries.associateBy { it.id }

        /** Total and non-throwing; the most common mode absorbs an id this build cannot read. */
        fun fromId(id: String): TrackingMode = byId[id] ?: WEIGHT_AND_REPS
    }
}

/**
 * Why a set was performed (PRD 9.4). Stored with a default of [WORKING] and given no V1
 * screen: no prototype exposes it, and PRD 11.2 counts warm-ups in the total anyway.
 */
enum class SetType(val id: String, val label: String) {
    WORKING("working", "Working"),
    WARMUP("warmup", "Warm-up"),
    DROP("drop", "Drop"),
    ;

    companion object {
        private val byId: Map<String, SetType> = entries.associateBy { it.id }

        /** Total and non-throwing; PRD 9.4 already makes [WORKING] the default. */
        fun fromId(id: String): SetType = byId[id] ?: WORKING
    }
}

/**
 * A named exercise, either shipped with the app or created on the phone (PRD 9.2).
 *
 * The V1 offers no way to rename or delete one, and a custom definition outlives every session
 * that used it.
 */
data class ExerciseDefinition(
    val id: ExerciseDefinitionId,
    val name: String,
    val trackingMode: TrackingMode,
    val equipment: EquipmentType? = null,
    val isCustom: Boolean = false,
) {
    /**
     * What makes a new `  bench press ` reuse `Bench press` instead of creating a second row
     * (PRD 9.2), and what the unique index of the catalogue stores. Folded with [Locale.ROOT]
     * so a name never folds two ways on two devices.
     */
    val nameFolded: String get() = fold(name)

    companion object {
        const val MAX_NAME_LENGTH: Int = 60

        fun fold(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}

/** One exercise inside one session, in the order the person arranged them (PRD 9.3). */
data class StrengthExercise(
    val id: StrengthExerciseId,
    val position: Int,
    val notes: String? = null,
)

/**
 * One set (PRD 9.4). Every numeric field is optional at this level: which of them a set must
 * carry is the tracking mode's business, and lives in `StrengthRules`.
 */
data class StrengthSet(
    val id: StrengthSetId,
    val position: Int,
    val setType: SetType = SetType.WORKING,
    val repetitions: Int? = null,
    val load: Load? = null,
    val duration: ActivityDuration? = null,
    val perceivedEffort: PerceivedEffort? = null,
) {
    companion object {
        /** PRD 9.4: strictly positive when present. The ceiling only guards a mistyped field. */
        val REPETITIONS_RANGE: IntRange = 1..999
    }
}

/** An exercise with the definition that gives it a name and a mode, and with its sets. */
data class StrengthExerciseDetail(
    val exercise: StrengthExercise,
    val definition: ExerciseDefinition,
    val sets: List<StrengthSet> = emptyList(),
)

/**
 * What PRD 11.4 shows under an exercise name: the last valid set of the most recent other
 * session that contained it. The rendering follows the mode, so the mode travels with the set.
 */
data class LastPerformance(
    val performedOn: LocalDate,
    val trackingMode: TrackingMode,
    val set: StrengthSet,
)
