package fr.kristenjestin.mue.domain.model

import java.util.UUID

/**
 * The four identifiers of the Activities module (PRD 8.2, 8.3, 9.2, 9.3, 9.4).
 *
 * Each is a value class over the `TEXT` UUID that PRD 16.3 stores, so a session id can
 * never be handed to a query expecting an exercise id even though both are strings at rest.
 */
@JvmInline
value class ActivityId(val value: String) {
    companion object {
        fun random(): ActivityId = ActivityId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ExerciseDefinitionId(val value: String) {
    companion object {
        fun random(): ExerciseDefinitionId = ExerciseDefinitionId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class StrengthExerciseId(val value: String) {
    companion object {
        fun random(): StrengthExerciseId = StrengthExerciseId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class StrengthSetId(val value: String) {
    companion object {
        fun random(): StrengthSetId = StrengthSetId(UUID.randomUUID().toString())
    }
}
