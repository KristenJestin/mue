package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.SessionEquipment

/**
 * One piece of gear attached to a timer (PRD 8.1, "identique dans son principe à
 * `SessionEquipment`").
 *
 * The domain type is [SessionEquipment] itself rather than a parallel one: FR-TIMER-007 copies
 * these rows into the session unchanged, and a second three-field class would only be the same
 * value under another name. The table is separate because the row belongs to a draft that the
 * cascade takes with it, and because a foreign key may not point at two parents.
 *
 * `custom_name_folded` is `NOT NULL DEFAULT ''` for the same reason as in
 * [SessionEquipmentEntity]: SQLite holds every `NULL` distinct inside a `UNIQUE` index, so a
 * nullable fold would let one draft carry two `treadmill` rows.
 */
@Entity(
    tableName = TimedDraftEquipmentEntity.TABLE_NAME,
    indices = [
        Index(value = ["draft_id"]),
        Index(
            value = ["draft_id", "equipment_type", "custom_name_folded"],
            unique = true,
        ),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TimedActivityDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TimedDraftEquipmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "draft_id")
    val draftId: String,

    @ColumnInfo(name = "equipment_type")
    val equipmentType: String,

    @ColumnInfo(name = "custom_name")
    val customName: String?,

    @ColumnInfo(name = "custom_name_folded", defaultValue = "''")
    val customNameFolded: String,

    @ColumnInfo(name = "position")
    val position: Int,
) {
    companion object {
        const val TABLE_NAME = "timed_draft_equipment"
    }
}

fun TimedDraftEquipmentEntity.toDomain(): SessionEquipment = SessionEquipment(
    equipmentType = EquipmentType.fromId(equipmentType),
    customName = customName,
    position = position,
)

/** The fold comes from the domain, so a name cannot fold one way in memory and another at rest. */
fun SessionEquipment.toDraftEntity(id: String, draftId: String): TimedDraftEquipmentEntity =
    TimedDraftEquipmentEntity(
        id = id,
        draftId = draftId,
        equipmentType = equipmentType.id,
        customName = customName,
        customNameFolded = customNameFolded,
        position = position,
    )
