package fr.kristenjestin.mue.data.local.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * `measurements`, `health_profile`, `sync_mutations` and `sync_aggregate_state` in memory, behind
 * the real DAO interfaces — so the JVM can run the journal logic that would otherwise only be
 * reachable from an emulator.
 *
 * ## Why this is worth having
 *
 * The three writes FR-SYNC-001 turns on — `sequenced`, `revisionOf`, `enqueueMutation` — live in
 * `@Transaction` **default methods** on [SyncJournalDao], [MeasurementDao] and [HealthProfileDao].
 * A default method is ordinary Kotlin: Room generates nothing for it and it calls the abstract
 * queries around it. Implementing those queries in memory therefore runs *the shipped code*, not
 * a re-statement of it, and the two properties that matter most — the outbox's order under a
 * clock that steps backwards, and the profile's write and its mutation happening together — stop
 * needing a device to be checked on every commit.
 *
 * ## What it deliberately does not claim
 *
 * There is no transaction here, so it cannot prove atomicity: "the profile did not survive a
 * journal write that failed" is a statement about SQLite and stays in the instrumented suite.
 * What it proves is what the default methods decide — which rows are written, with which values
 * and in which order. The SQL those rows are then read back with is asserted there too.
 *
 * The queries below mirror their `@Query` annotations exactly, including the ordering
 * (`created_at`, then insertion order for `rowid`) and the `IN`/`NOT IN` filters, because a fake
 * that ordered differently would let a test pass that the database would fail.
 */
class InMemoryJournal {

    /** Insertion order stands in for `rowid`, which is what the real tie-break reads. */
    private val mutations = LinkedHashMap<String, SyncMutationEntity>()
    private val aggregateState = LinkedHashMap<Pair<String, String>, SyncAggregateStateEntity>()

    fun enqueue(mutation: SyncMutationEntity) {
        check(mutations.put(mutation.mutationId, mutation) == null) {
            // `OnConflictStrategy.ABORT`: a colliding mutation id is a bug, not a row to replace.
            "sync_mutations already holds ${mutation.mutationId}"
        }
    }

    fun highestStamp(): Long? = mutations.values.maxOfOrNull { it.createdAt }

    fun revisionOf(aggregateType: String, aggregateId: String): Long? =
        aggregateState[aggregateType to aggregateId]?.revision

    fun insertStateIfAbsent(state: SyncAggregateStateEntity) {
        aggregateState.putIfAbsent(state.aggregateType to state.aggregateId, state)
    }

    fun markDeleted(
        aggregateType: String,
        aggregateId: String,
        deletedAt: Long,
        mutationId: String,
    ) {
        aggregateState.computeIfPresent(aggregateType to aggregateId) { _, row ->
            row.copy(deletedAt = deletedAt, lastMutationId = mutationId)
        }
    }

    fun markAlive(aggregateType: String, aggregateId: String, mutationId: String) {
        aggregateState.computeIfPresent(aggregateType to aggregateId) { _, row ->
            row.copy(deletedAt = null, lastMutationId = mutationId)
        }
    }

    fun state(aggregateType: String, aggregateId: String): SyncAggregateStateEntity? =
        aggregateState[aggregateType to aggregateId]

    /** The server acknowledged a mutation: what `SyncDao.recordAcceptedRevision` writes. */
    fun recordAcceptedRevision(aggregateType: String, aggregateId: String, revision: Long) {
        aggregateState.computeIfPresent(aggregateType to aggregateId) { _, row ->
            row.copy(revision = revision)
        }
    }

    /** `SyncDao.pendingMutations`: `state = 'pending'`, `ORDER BY created_at ASC, rowid ASC`. */
    fun pending(limit: Int = Int.MAX_VALUE): List<SyncMutationEntity> =
        mutations.values
            .filter { it.state == SyncMutationEntity.STATE_PENDING }
            .withIndex()
            .sortedWith(compareBy({ it.value.createdAt }, { it.index }))
            .map { it.value }
            .take(limit)

    /** `SyncDao.pendingMutationsOfTypes`, filtering before the window as the query does. */
    fun pendingOfTypes(aggregateTypes: List<String>, limit: Int): List<SyncMutationEntity> =
        pending().filter { it.aggregateType in aggregateTypes }.take(limit)

    fun all(): List<SyncMutationEntity> = mutations.values.toList()

    fun mutation(mutationId: String): SyncMutationEntity? = mutations[mutationId]

    fun markFailed(mutationId: String) {
        mutations.computeIfPresent(mutationId) { _, row ->
            row.copy(state = SyncMutationEntity.STATE_FAILED)
        }
    }

    fun delete(mutationId: String) {
        mutations.remove(mutationId)
    }
}

/** [MeasurementDao] over [InMemoryJournal]; every default method on it runs unchanged. */
class InMemoryMeasurementDao(private val journal: InMemoryJournal) : MeasurementDao {

    private val rows = MutableStateFlow<Map<String, MeasurementEntity>>(emptyMap())

    override fun observeAll(): Flow<List<MeasurementEntity>> =
        rows.map { it.values.sortedBy { row -> row.date } }

    override fun observeInWindow(start: String?, end: String?): Flow<List<MeasurementEntity>> =
        observeAll().map { all ->
            all.filter { (start == null || it.date >= start) && (end == null || it.date <= end) }
        }

    override fun observeLatest(): Flow<MeasurementEntity?> = observeAll().map { it.lastOrNull() }

    override suspend fun getAll(): List<MeasurementEntity> =
        rows.value.values.sortedBy { it.date }

    override suspend fun findByDate(date: String): MeasurementEntity? = rows.value[date]

    override suspend fun count(): Int = rows.value.size

    override suspend fun upsert(entity: MeasurementEntity) {
        rows.value = rows.value + (entity.date to entity)
    }

    override suspend fun deleteByDate(date: String) {
        rows.value = rows.value - date
    }

    override suspend fun enqueueMutation(mutation: SyncMutationEntity) = journal.enqueue(mutation)

    override suspend fun highestMutationStamp(): Long? = journal.highestStamp()

    override suspend fun revisionOf(aggregateType: String, aggregateId: String): Long? =
        journal.revisionOf(aggregateType, aggregateId)

    override suspend fun insertAggregateStateIfAbsent(state: SyncAggregateStateEntity) =
        journal.insertStateIfAbsent(state)

    override suspend fun markAggregateDeleted(
        aggregateType: String,
        aggregateId: String,
        deletedAt: Long,
        mutationId: String,
    ) = journal.markDeleted(aggregateType, aggregateId, deletedAt, mutationId)

    override suspend fun markAggregateAlive(
        aggregateType: String,
        aggregateId: String,
        mutationId: String,
    ) = journal.markAlive(aggregateType, aggregateId, mutationId)
}

/** [HealthProfileDao] over the same [InMemoryJournal], so both queues are the one queue. */
class InMemoryHealthProfileDao(private val journal: InMemoryJournal) : HealthProfileDao {

    private val row = MutableStateFlow<HealthProfileEntity?>(null)

    override fun observe(): Flow<HealthProfileEntity?> = row

    override suspend fun get(): HealthProfileEntity? = row.value

    override suspend fun upsert(entity: HealthProfileEntity) {
        row.value = entity
    }

    override suspend fun insertIfAbsent(entity: HealthProfileEntity) {
        if (row.value == null) row.value = entity
    }

    override suspend fun clear() {
        row.value = null
    }

    override suspend fun enqueueMutation(mutation: SyncMutationEntity) = journal.enqueue(mutation)

    override suspend fun highestMutationStamp(): Long? = journal.highestStamp()

    override suspend fun revisionOf(aggregateType: String, aggregateId: String): Long? =
        journal.revisionOf(aggregateType, aggregateId)

    override suspend fun insertAggregateStateIfAbsent(state: SyncAggregateStateEntity) =
        journal.insertStateIfAbsent(state)

    override suspend fun markAggregateDeleted(
        aggregateType: String,
        aggregateId: String,
        deletedAt: Long,
        mutationId: String,
    ) = journal.markDeleted(aggregateType, aggregateId, deletedAt, mutationId)

    override suspend fun markAggregateAlive(
        aggregateType: String,
        aggregateId: String,
        mutationId: String,
    ) = journal.markAlive(aggregateType, aggregateId, mutationId)
}
