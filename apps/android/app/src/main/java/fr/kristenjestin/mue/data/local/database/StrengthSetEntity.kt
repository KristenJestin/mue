package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.SetType
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId

/**
 * One set (PRD 9.4). Every quantity is a whole number in its canonical unit — grams for a load,
 * seconds for a hold — so nothing here is ever a float.
 *
 * A field the mode does not use is null, never zero. Which fields a set must carry is the
 * tracking mode's business and stays in `StrengthRules`: the save path filters through it, so a
 * row that reaches this table is already valid and no query has to re-express the rule.
 */
@Entity(
    tableName = StrengthSetEntity.TABLE_NAME,
    indices = [Index(value = ["strength_exercise_id"])],
    foreignKeys = [
        ForeignKey(
            entity = StrengthExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["strength_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StrengthSetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "strength_exercise_id")
    val strengthExerciseId: String,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "set_type", defaultValue = "'working'")
    val setType: String,

    @ColumnInfo(name = "repetitions")
    val repetitions: Int?,

    @ColumnInfo(name = "load_grams")
    val loadGrams: Int?,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int?,

    @ColumnInfo(name = "perceived_effort")
    val perceivedEffort: Int?,
) {
    companion object {
        const val TABLE_NAME = "strength_sets"
    }
}

fun StrengthSetEntity.toDomain(): StrengthSet = StrengthSet(
    id = StrengthSetId(id),
    position = position,
    setType = SetType.fromId(setType),
    repetitions = repetitions,
    load = loadGrams.toLoadColumn(),
    duration = durationSeconds.toDurationColumn(),
    perceivedEffort = perceivedEffort.toPerceivedEffortColumn(),
)

fun StrengthSet.toEntity(strengthExerciseId: String): StrengthSetEntity = StrengthSetEntity(
    id = id.value,
    strengthExerciseId = strengthExerciseId,
    position = position,
    setType = setType.id,
    repetitions = repetitions,
    loadGrams = load?.grams,
    durationSeconds = duration?.seconds,
    perceivedEffort = perceivedEffort?.value,
)
