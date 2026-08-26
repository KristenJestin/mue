package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * What the server knows about one aggregate, and the local tombstone store (FR-SYNC-005).
 *
 * The business row and this row are deliberately separate. On a delete the business row goes
 * and this one stays with [deletedAt] set, so an offline copy of the same aggregate arriving
 * later is recognised as stale instead of being resurrected — which is the entire purpose of a
 * tombstone.
 *
 * [revision] is the per-aggregate counter of PRD 12.1, used for optimistic concurrency. It is
 * not the sync cursor, which is global to the account and lives in [SyncStateEntity];
 * conflating the two loses changes.
 *
 * It is **null until the server has accepted a mutation** for this aggregate, and null is not
 * zero: zero would claim a revision the server issued, and a mutation quoting it would be
 * rejected as a stale edit of something that does not exist. Null is what PRD 12.2 calls "la
 * révision de base connue par l'auteur, si elle existe", and it is what says "this is a
 * creation".
 */
@Entity(
    tableName = SyncAggregateStateEntity.TABLE_NAME,
    primaryKeys = ["aggregate_type", "aggregate_id"],
)
data class SyncAggregateStateEntity(
    @ColumnInfo(name = "aggregate_type")
    val aggregateType: String,

    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,

    @ColumnInfo(name = "revision")
    val revision: Long? = null,

    @ColumnInfo(name = "server_updated_at")
    val serverUpdatedAt: Long? = null,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "last_mutation_id")
    val lastMutationId: String? = null,

    @ColumnInfo(name = "origin_type")
    val originType: String? = null,

    @ColumnInfo(name = "origin_id")
    val originId: String? = null,
) {
    companion object {
        const val TABLE_NAME = "sync_aggregate_state"

        /**
         * The aggregates of PRD 10.2. A `Measurement` is keyed by its date: the date is already
         * the primary key on both sides — the server's `measurements` is keyed by
         * `(user_id, date)` — so it is the stable identity and no id has to be invented for it.
         */
        const val TYPE_MEASUREMENT = "measurement"
        const val TYPE_HEALTH_PROFILE = "healthProfile"
        const val TYPE_ACTIVITY_SESSION = "activitySession"
        const val TYPE_CUSTOM_EXERCISE = "customExerciseDefinition"

        const val ORIGIN_ANDROID = "android"
        const val ORIGIN_AGENT = "agent"
        const val ORIGIN_SERVER = "server"
    }
}
