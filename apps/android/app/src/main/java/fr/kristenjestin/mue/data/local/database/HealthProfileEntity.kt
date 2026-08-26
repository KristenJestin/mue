package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The two synchronised fields of the health profile (sync PRD 10.1), in Room rather than in the
 * Preferences file that has held them until now.
 *
 * They move because PRD 19 requires a remote aggregate to be applied *and* its cursor advanced
 * in one local transaction, and DataStore does not join a Room transaction — no amount of care
 * in the sync engine can make two stores commit together. `displayName` stays in DataStore: it
 * is not synchronised, so it has nothing to be atomic with.
 *
 * Both fields stay optional, exactly as `UserProfile` has them: the app is fully usable with an
 * empty profile. The birth date is ISO text like every other date here, so lexicographic order
 * is chronological order and no wire format has to be agreed separately.
 *
 * `id` is always [ROW_ID]. As in [SyncStateEntity], the single-row rule is a constant primary
 * key and not a `CHECK`, which Room cannot emit and which would therefore exist on a migrated
 * file and not on a fresh one.
 */
@Entity(tableName = HealthProfileEntity.TABLE_NAME)
data class HealthProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = ROW_ID,

    @ColumnInfo(name = "height_cm")
    val heightCm: Int? = null,

    @ColumnInfo(name = "birth_date")
    val birthDate: String? = null,
) {
    companion object {
        const val TABLE_NAME = "health_profile"
        const val ROW_ID = "me"
    }
}
