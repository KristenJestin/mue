package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single row that records which server this phone is paired with and how far it has read.
 *
 * There is deliberately **no token column**: sync PRD 9.2 and 16 put the session bearer in
 * Android Keystore, and a column here would place it in a file any backup or `adb` pull could
 * carry off. `SyncTokenStore` owns it instead.
 *
 * [cursor] is the opaque server cursor of PRD 12.3, stored exactly as it arrived. It is text on
 * purpose: no client may do arithmetic on it, and text cannot be incremented by accident.
 *
 * The row is absent until something writes it, and an absent row reads as "no server, nothing
 * seeded". `id` is always [ROW_ID]. The single-row rule is a constant primary key rather than
 * the `CHECK (id = 0)` one would write by hand, because Room cannot emit a `CHECK`: the
 * constraint would then exist on an upgraded file and not on a freshly created one, and the
 * two have to be the same database.
 */
@Entity(tableName = SyncStateEntity.TABLE_NAME)
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = ROW_ID,

    @ColumnInfo(name = "server_url")
    val serverUrl: String? = null,

    @ColumnInfo(name = "server_name")
    val serverName: String? = null,

    @ColumnInfo(name = "account_id")
    val accountId: String? = null,

    @ColumnInfo(name = "device_id")
    val deviceId: String? = null,

    @ColumnInfo(name = "cursor")
    val cursor: String? = null,

    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long? = null,

    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,

    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String? = null,

    /**
     * Whether the one-shot copy of the height and birth date out of DataStore has run. That
     * copy cannot be a Room `Migration` — a `SupportSQLiteDatabase` has no way to read a
     * Preferences file — so it is a startup task, and this flag is what makes it happen once.
     */
    @ColumnInfo(name = "profile_seeded")
    val profileSeeded: Boolean = false,
) {
    companion object {
        const val TABLE_NAME = "sync_state"
        const val ROW_ID = 0
    }
}
