package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.SessionEquipment

/**
 * One piece of gear attached to a session (PRD 8.4).
 *
 * The row keeps a key of its own because `other` lets one session carry several free names;
 * nothing in the domain refers to a piece of equipment by id, so [SessionEquipment] has none and
 * the mapper is what mints one.
 *
 * `custom_name_folded` is `NOT NULL DEFAULT ''` rather than nullable, and that is the whole point
 * of the column: SQLite considers every `NULL` distinct inside a `UNIQUE` index, so a nullable
 * fold would happily accept two `treadmill` rows on one session. The empty string collides with
 * itself, which is exactly what PRD FR-ACTIVITY-008 asks for.
 */
@Entity(
    tableName = SessionEquipmentEntity.TABLE_NAME,
    indices = [
        Index(value = ["session_id"]),
        Index(
            value = ["session_id", "equipment_type", "custom_name_folded"],
            unique = true,
        ),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionEquipmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

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
        const val TABLE_NAME = "session_equipment"
    }
}

fun SessionEquipmentEntity.toDomain(): SessionEquipment = SessionEquipment(
    equipmentType = EquipmentType.fromId(equipmentType),
    customName = customName,
    position = position,
)

/** The fold is taken from the domain, so a name can never fold one way in memory and another at rest. */
fun SessionEquipment.toEntity(id: String, sessionId: String): SessionEquipmentEntity =
    SessionEquipmentEntity(
        id = id,
        sessionId = sessionId,
        equipmentType = equipmentType.id,
        customName = customName,
        customNameFolded = customNameFolded,
        position = position,
    )
