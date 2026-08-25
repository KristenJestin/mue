package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.TrackingMode

/**
 * A named exercise, either shipped with the app or created on the phone (PRD 9.2).
 *
 * `UNIQUE(name_folded)` is what makes "a name already in the catalogue reuses its definition
 * instead of adding a second one" a property of the database rather than of a lookup someone
 * might forget. The fold itself comes from [ExerciseDefinition.fold], which lowercases with
 * `Locale.ROOT` so `Incline` never folds to `ıncline` on a Turkish phone.
 *
 * The V1 offers no screen to rename or delete a definition, and a custom one outlives every
 * session that used it — hence the `ON DELETE RESTRICT` on the exercises that point here.
 */
@Entity(
    tableName = ExerciseDefinitionEntity.TABLE_NAME,
    indices = [Index(value = ["name_folded"], unique = true)],
)
data class ExerciseDefinitionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "name_folded")
    val nameFolded: String,

    @ColumnInfo(name = "tracking_mode")
    val trackingMode: String,

    @ColumnInfo(name = "equipment")
    val equipment: String?,

    @ColumnInfo(name = "is_custom")
    val isCustom: Boolean,
) {
    companion object {
        const val TABLE_NAME = "exercise_definitions"
    }
}

fun ExerciseDefinitionEntity.toDomain(): ExerciseDefinition = ExerciseDefinition(
    id = ExerciseDefinitionId(id),
    name = name,
    trackingMode = TrackingMode.fromId(trackingMode),
    equipment = equipment.toEquipmentTypeColumn(),
    isCustom = isCustom,
)

fun ExerciseDefinition.toEntity(): ExerciseDefinitionEntity = ExerciseDefinitionEntity(
    id = id.value,
    name = name,
    nameFolded = nameFolded,
    trackingMode = trackingMode.id,
    equipment = equipment.toColumn(),
    isCustom = isCustom,
)
