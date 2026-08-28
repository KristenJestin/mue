package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The single health profile row. Every query names the row rather than trusting the table to
 * hold exactly one, so a second row could never answer a read by accident. `'me'` is
 * [HealthProfileEntity.ROW_ID].
 *
 * It inherits [SyncJournalDao] for the same reason [MeasurementDao] does: sync PRD 13.4 makes
 * the health profile a synchronised aggregate, so a height typed on the phone has to reach the
 * outbox in the transaction that writes it, not in a second one a process death can skip.
 */
@Dao
interface HealthProfileDao : SyncJournalDao {

    @Query("SELECT * FROM health_profile WHERE id = 'me'")
    fun observe(): Flow<HealthProfileEntity?>

    @Query("SELECT * FROM health_profile WHERE id = 'me'")
    suspend fun get(): HealthProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HealthProfileEntity)

    /** Used by the one-shot seeding, which must not overwrite a profile already in Room. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: HealthProfileEntity)

    /**
     * Removes the single row. It exists for one caller and one case: a `delete` change arriving
     * from the server for this aggregate.
     *
     * Sync PRD 13.4 gives the profile no deletion and `packages/domain` refuses a `delete`
     * mutation for it outright, so no Mue Platform can produce such a change. The branch exists
     * anyway because the alternative is worse: `RoomSyncStore.applyChange` must be total, and a
     * change it threw on would roll back its page and leave the cursor stuck on data the phone
     * can never move past (PRD 12.4 is about *not applying*, not about never advancing). So an
     * unexpected tombstone is applied as what a tombstone means — the aggregate is gone — which
     * loses nothing the server still holds and lets the cursor advance.
     */
    @Query("DELETE FROM health_profile WHERE id = 'me'")
    suspend fun clear()

    /**
     * [upsert] with the outbox row it was missing (FR-SYNC-001).
     *
     * Until this existed, `health_profile` journalled nothing: `DataStoreUserProfileRepository`
     * called [upsert] alone, so a height or a birth date changed on the phone was a local write
     * with no trace that it still had to be sent, and FR-SYNC-001's "une mutation ne peut pas
     * être perdue" held for measurements only. [SyncAggregateStateEntity.TYPE_HEALTH_PROFILE]
     * and PRD 13.4 both already said this aggregate was synchronised.
     *
     * The profile is one aggregate with one identity — there is exactly one row, keyed
     * [HealthProfileEntity.ROW_ID] — so there is no tombstone path here: PRD 13.4 gives the
     * profile no deletion, only fields that become null. Clearing a height is an upsert whose
     * payload says null, which is a fact the server can merge field by field; a tombstone would
     * say the profile itself ceased to exist, which is not a state the domain has.
     */
    @Transaction
    suspend fun upsertWithMutation(entity: HealthProfileEntity, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        upsert(entity)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }
}
