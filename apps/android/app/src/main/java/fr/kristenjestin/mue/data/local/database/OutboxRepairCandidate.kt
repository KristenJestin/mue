package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo

/**
 * One outbox row as a repair pass needs to see it: what it is called, where it is in the queue,
 * and what was last said about it.
 *
 * A projection and not [SyncMutationEntity], because the difference is the payload. `Data & sync`
 * shows a handful of rows; an outbox that has not drained for a year holds a year of them, and
 * reading every stored payload into memory at every engine start to answer a question about
 * identifiers would make a cheap pass expensive in exactly the case it exists for.
 *
 * There is no `state` constant repeated here: the values are [SyncMutationEntity]'s, and this
 * only carries them across.
 */
data class OutboxRepairCandidate(
    @ColumnInfo(name = "mutation_id")
    val mutationId: String,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)
