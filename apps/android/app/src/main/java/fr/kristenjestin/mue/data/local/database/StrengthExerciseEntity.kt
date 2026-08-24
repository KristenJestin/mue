package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId

/**
 * One exercise inside one session, in the order the person arranged them (PRD 9.3).
 *
 * The two foreign keys are deliberately asymmetric. Deleting a session takes its exercises with
 * it, but the definition they name is `ON DELETE RESTRICT`: PRD 9.2 keeps a definition for good,
 * including once no session uses it any more, so a cascade there would let one deleted session
 * quietly empty the catalogue.
 */
@Entity(
    tableName = StrengthExerciseEntity.TABLE_NAME,
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["exercise_definition_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_definition_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class StrengthExerciseEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "exercise_definition_id")
    val exerciseDefinitionId: String,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "notes")
    val notes: String?,
) {
    companion object {
        const val TABLE_NAME = "strength_exercises"
    }
}

/**
 * An exercise read back with the definition that gives it a name and a mode. The join is done in
 * one query rather than one per exercise, so reopening a twelve-exercise session stays a
 * fixed number of statements.
 */
data class StrengthExerciseWithDefinition(
    @Embedded val exercise: StrengthExerciseEntity,
    @Embedded(prefix = "definition_") val definition: ExerciseDefinitionEntity,
)

fun StrengthExerciseEntity.toDomain(): StrengthExercise = StrengthExercise(
    id = StrengthExerciseId(id),
    position = position,
    notes = notes,
)

fun StrengthExerciseDetail.toEntity(sessionId: String): StrengthExerciseEntity =
    StrengthExerciseEntity(
        id = exercise.id.value,
        sessionId = sessionId,
        exerciseDefinitionId = definition.id.value,
        position = exercise.position,
        notes = exercise.notes,
    )
