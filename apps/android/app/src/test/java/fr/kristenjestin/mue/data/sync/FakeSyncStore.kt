package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncWire

/**
 * [SyncStore] in memory, with the same rules the Room implementation enforces in SQL.
 *
 * It is a fake and not a mock: it holds real rows, moves them between real states and refuses
 * the things SQLite would refuse, so a test that passes against it is a test about the engine's
 * decisions rather than about the calls the engine happened to make. `pending` returns only
 * `pending` rows in stamp order, exactly as `SyncDao.pendingMutations` does, because the engine's
 * behaviour when a mutation is `failed` or `inflight` is most of what is worth asserting.
 */
class FakeSyncStore(
    var serverUrl: String? = "https://mue.home.arpa",
    var deviceId: String? = "device-7f3c1a04",
    initialCursor: String? = null,
    mutations: List<SyncMutationEntity> = emptyList(),
) : SyncStore {

    private val rows = mutations.associateBy { it.mutationId }.toMutableMap()

    var cursor: String? = initialCursor
        private set

    /** Every cursor ever written, so a test can assert it never moved rather than only where. */
    val cursorHistory = mutableListOf<String>()

    val revisions = mutableMapOf<Pair<String, String>, Long>()
    val applied = mutableListOf<SyncChangeDto>()
    var lastSuccessAt: Long? = null
    var lastFailure: Pair<String?, String?>? = null
    var requeueInflightCalls: Int = 0
        private set

    /** Every call, in order, so "recovery ran before anything else" is assertable. */
    val calls = mutableListOf<String>()

    fun rowsInState(state: String): List<SyncMutationEntity> =
        rows.values.filter { it.state == state }.sortedBy { it.createdAt }

    fun row(mutationId: String): SyncMutationEntity? = rows[mutationId]

    /** How many of the next [requeueInflight] calls throw, so a failed recovery is testable. */
    var requeueInflightFailures: Int = 0

    override suspend fun requeueInflight(): Int {
        calls += "requeueInflight"
        requeueInflightCalls++
        if (requeueInflightFailures > 0) {
            requeueInflightFailures--
            throw IllegalStateException("the database could not be opened")
        }
        val stranded = rows.values.filter { it.state == SyncMutationEntity.STATE_INFLIGHT }
        stranded.forEach { rows[it.mutationId] = it.copy(state = SyncMutationEntity.STATE_PENDING) }
        return stranded.size
    }

    override suspend fun serverUrl(): String? = serverUrl.also { calls += "serverUrl" }

    override suspend fun deviceId(): String? = deviceId

    override suspend fun cursor(): String? = cursor

    /**
     * Only the sendable aggregate types, exactly as `SyncDao.pendingMutationsOfTypes` selects
     * them — and the filter is applied *before* [limit], which is the whole point of it: a fake
     * that took the window first and filtered afterwards would let the engine pass a test that
     * the database would fail, in precisely the case (a queue full of undeliverable rows) the
     * filter exists for.
     */
    override suspend fun pending(limit: Int): List<SyncMutationEntity> {
        calls += "pending"
        return rows.values
            .filter { it.state == SyncMutationEntity.STATE_PENDING }
            .filter { it.aggregateType in SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES }
            .sortedWith(compareBy({ it.createdAt }, { it.mutationId }))
            .take(limit)
    }

    override suspend fun deferredCount(): Int = rows.values.count {
        it.state == SyncMutationEntity.STATE_PENDING &&
            it.aggregateType !in SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES
    }

    override suspend fun markInflight(mutationIds: List<String>) {
        calls += "markInflight(${mutationIds.size})"
        setState(mutationIds, SyncMutationEntity.STATE_INFLIGHT)
    }

    override suspend fun requeuePending(mutationIds: List<String>) {
        calls += "requeuePending(${mutationIds.size})"
        setState(mutationIds, SyncMutationEntity.STATE_PENDING)
    }

    override suspend fun acknowledge(mutation: SyncMutationEntity, revision: Long?, at: Long) {
        calls += "acknowledge(${mutation.mutationId})"
        if (revision != null) {
            revisions[mutation.aggregateType to mutation.aggregateId] = revision
        }
        rows.remove(mutation.mutationId)
    }

    override suspend fun reject(mutationId: String, code: String?, message: String?) {
        calls += "reject($mutationId)"
        rows[mutationId] = rows.getValue(mutationId).copy(
            state = SyncMutationEntity.STATE_FAILED,
            attemptCount = rows.getValue(mutationId).attemptCount + 1,
            lastErrorCode = code,
            lastErrorMessage = message,
        )
    }

    override suspend fun applyPage(changes: List<SyncChangeDto>, nextCursor: String, at: Long) {
        calls += "applyPage(${changes.size})"
        applied += changes
        cursor = nextCursor
        cursorHistory += nextCursor
        lastSuccessAt = at
        lastFailure = null
    }

    override suspend fun recordFailure(code: String?, message: String?) {
        calls += "recordFailure($code)"
        lastFailure = code to message
    }

    private fun setState(mutationIds: List<String>, state: String) {
        mutationIds.forEach { id -> rows[id]?.let { rows[id] = it.copy(state = state) } }
    }
}
