package fr.kristenjestin.mue.data.local.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SyncDao] as the two screens of sync PRD 9 see it: one `sync_state` row and three counters.
 *
 * Deliberately *not* an in-memory outbox. [InMemoryJournal] next door exists because the journal's
 * `@Transaction` default methods are shipped code worth running on the JVM; nothing of the sort is
 * true here. `SyncViewModel` reads five of this interface's thirty-seven members, and every other
 * one is answered by [unused] rather than by a plausible-looking implementation — a fake that
 * quietly returned an empty list for a query the view model was never supposed to make would turn
 * a new dependency into a passing test instead of a failing one.
 *
 * The counters are settable so a status can be posed, the row is settable so a pairing can appear
 * or disappear between two reads, and both are `MutableStateFlow`s so `observe*` and the suspend
 * reads can never disagree about what the table holds.
 */
class FakeSyncDao(
    initialState: SyncStateEntity? = null,
    initialPending: Int = 0,
    initialFailed: Int = 0,
) : SyncDao {

    private val rows = MutableStateFlow(initialState)
    private val pendingCount = MutableStateFlow(initialPending)
    private val failedCount = MutableStateFlow(initialFailed)

    /** What `sync_state` holds now; assigning it is a pairing made or given up. */
    var state: SyncStateEntity?
        get() = rows.value
        set(value) {
            rows.value = value
        }

    override fun observeSyncState(): Flow<SyncStateEntity?> = rows.asStateFlow()

    override suspend fun syncState(): SyncStateEntity? = rows.value

    override fun observePendingCount(): Flow<Int> = pendingCount.asStateFlow()

    override fun observeFailedCount(): Flow<Int> = failedCount.asStateFlow()

    override suspend fun countPendingOfOtherTypes(aggregateTypes: List<String>): Int = 0

    // --- everything the two sync screens never ask for -------------------------------------------

    private fun unused(query: String): Nothing =
        error("$query is not part of what SyncViewModel reads")

    override suspend fun pendingMutations(limit: Int) = unused("pendingMutations")

    override suspend fun pendingMutationsOfTypes(aggregateTypes: List<String>, limit: Int) =
        unused("pendingMutationsOfTypes")

    override suspend fun mutation(mutationId: String) = unused("mutation")

    override suspend fun countInState(state: String) = unused("countInState")

    override suspend fun setState(mutationIds: List<String>, state: String) = unused("setState")

    override suspend fun requeueInflight() = unused("requeueInflight")

    override suspend fun repairCandidates() = unused("repairCandidates")

    override suspend fun remintMutationId(previousMutationId: String, mutationId: String) =
        unused("remintMutationId")

    override suspend fun unjournalledActivitySessions(aggregateType: String) =
        unused("unjournalledActivitySessions")

    override suspend fun unjournalledCustomExercises(aggregateType: String) =
        unused("unjournalledCustomExercises")

    override suspend fun exerciseDefinition(id: String) = unused("exerciseDefinition")

    override suspend fun aggregateIdRepairCandidates(aggregateType: String) =
        unused("aggregateIdRepairCandidates")

    override suspend fun renameMutationAggregateId(mutationId: String, aggregateId: String) =
        unused("renameMutationAggregateId")

    override suspend fun aggregateStatesOfType(aggregateType: String) =
        unused("aggregateStatesOfType")

    override suspend fun renameAggregateState(
        aggregateType: String,
        previousAggregateId: String,
        aggregateId: String,
    ) = unused("renameAggregateState")

    override suspend fun recordAcceptedRevision(
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        mutationId: String,
        serverUpdatedAt: Long,
    ) = unused("recordAcceptedRevision")

    override suspend fun markFailed(mutationId: String, errorCode: String?, errorMessage: String?) =
        unused("markFailed")

    override suspend fun deleteMutation(mutationId: String) = unused("deleteMutation")

    override suspend fun aggregateState(aggregateType: String, aggregateId: String) =
        unused("aggregateState")

    override suspend fun tombstones(aggregateType: String) = unused("tombstones")

    override suspend fun putAggregateState(state: SyncAggregateStateEntity) =
        unused("putAggregateState")

    override suspend fun insertSyncStateIfAbsent(state: SyncStateEntity) =
        unused("insertSyncStateIfAbsent")

    override suspend fun putSyncState(state: SyncStateEntity) = unused("putSyncState")

    override suspend fun recordSuccess(cursor: String?, at: Long) = unused("recordSuccess")

    override suspend fun recordFailure(errorCode: String?, errorMessage: String?) =
        unused("recordFailure")

    override suspend fun markProfileSeeded() = unused("markProfileSeeded")

    override suspend fun enqueueMutation(mutation: SyncMutationEntity) = unused("enqueueMutation")

    override suspend fun highestMutationStamp() = unused("highestMutationStamp")

    override suspend fun revisionOf(aggregateType: String, aggregateId: String) =
        unused("revisionOf")

    override suspend fun insertAggregateStateIfAbsent(state: SyncAggregateStateEntity) =
        unused("insertAggregateStateIfAbsent")

    override suspend fun markAggregateDeleted(
        aggregateType: String,
        aggregateId: String,
        deletedAt: Long,
        mutationId: String,
    ) = unused("markAggregateDeleted")

    override suspend fun markAggregateAlive(
        aggregateType: String,
        aggregateId: String,
        mutationId: String,
    ) = unused("markAggregateAlive")
}
