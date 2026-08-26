package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The outbox: one row per local change waiting to reach the server (sync PRD FR-SYNC-001).
 *
 * A row is written inside the very transaction that writes the business row, so there is no
 * window in which a process death could keep the change and lose the mutation.
 *
 * [mutationId] is the idempotency key of FR-SYNC-006: the server stores it and replays the
 * stored result rather than applying the change twice, so a lost response costs a retry and
 * never a duplicate.
 *
 * [baseRevision] is the revision the change was computed from, read from
 * [SyncAggregateStateEntity] in the same transaction. Null means the server has never
 * acknowledged this aggregate, which is a creation rather than an unknown.
 *
 * No float: every instant is epoch milliseconds, as in `activity_sessions`.
 */
@Entity(
    tableName = SyncMutationEntity.TABLE_NAME,
    indices = [Index(value = ["state", "created_at"])],
)
data class SyncMutationEntity(
    @PrimaryKey
    @ColumnInfo(name = "mutation_id")
    val mutationId: String,

    @ColumnInfo(name = "aggregate_type")
    val aggregateType: String,

    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,

    @ColumnInfo(name = "op")
    val op: String,

    @ColumnInfo(name = "base_revision")
    val baseRevision: Long?,

    /** The whole aggregate for an `upsert` (PRD 12.2); null for a `delete`, which carries none. */
    @ColumnInfo(name = "payload")
    val payload: String?,

    @ColumnInfo(name = "payload_schema_version")
    val payloadSchemaVersion: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,

    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String?,
) {
    companion object {
        const val TABLE_NAME = "sync_mutations"

        /**
         * FR-SYNC-007: a rejected mutation is kept, marked [STATE_FAILED] and skipped by every
         * later send. Deleting it would lose a change the user made; leaving it
         * [STATE_PENDING] would let one bad row stall every mutation queued behind it.
         */
        const val STATE_PENDING = "pending"
        const val STATE_INFLIGHT = "inflight"
        const val STATE_FAILED = "failed"

        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
    }
}
