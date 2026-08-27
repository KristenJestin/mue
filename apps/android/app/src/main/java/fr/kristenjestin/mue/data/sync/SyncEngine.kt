package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.remote.sync.MueErrorDto
import fr.kristenjestin.mue.data.remote.sync.MutationAppliedDto
import fr.kristenjestin.mue.data.remote.sync.MutationDuplicateDto
import fr.kristenjestin.mue.data.remote.sync.MutationEnvelopeDto
import fr.kristenjestin.mue.data.remote.sync.MutationRejectedDto
import fr.kristenjestin.mue.data.remote.sync.PullPageDto
import fr.kristenjestin.mue.data.remote.sync.PullRequestDto
import fr.kristenjestin.mue.data.remote.sync.PullUpgradeRequiredDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.SyncApi
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.data.remote.sync.SyncTransportException
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.remote.sync.WIRE_PULL_DEFAULT_LIMIT
import fr.kristenjestin.mue.data.remote.sync.WIRE_PUSH_MAX_MUTATIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * One synchronisation: send what is waiting, read what is new, apply it, advance the cursor.
 *
 * This is FR-SYNC-002's five steps, in its order and with its guarantees, and every deviation
 * from the obvious implementation below is one of them:
 *
 * 1. **Recovery runs before anything else.** Rows left `inflight` by a killed process are
 *    returned to `pending` at engine start, once. Without that, a phone that dies mid-send
 *    keeps the user's change and never sends it again — the exact loss FR-SYNC-001 forbids.
 * 2. **Push precedes pull.** A change accepted by the server comes back in the same journal,
 *    so pushing first means the page the phone applies already contains its own work and the
 *    two cannot fight (PRD 13.2's "la dernière mutation acceptée par le serveur").
 * 3. **Mutation ids are never minted here.** Each comes from `sync_mutations.mutation_id`, put
 *    there by the transaction that wrote the business row. A retry therefore carries the id the
 *    server already knows, and FR-SYNC-006's replay returns the stored result rather than
 *    applying the change twice. Nothing in this class, in `SyncWire` or in `SyncApi` calls
 *    `UUID.randomUUID()`; if anything did, a lost response would become a duplicate weight.
 * 4. **Every failure path returns rows to `pending`.** Not on the happy path, not on the
 *    exceptions we expect: in a `finally`, under `NonCancellable`, so a cancelled worker leaves
 *    the outbox exactly as it found it. The `inflight` state exists only for the width of one
 *    request.
 * 5. **The cursor advances only after a page is applied**, in the same transaction, and never
 *    past a change this build cannot apply (PRD 12.4).
 * 6. **A send selects only what it can send.** The activity, custom exercise and food
 *    aggregates are journalled at every save and `packages/contracts` has no branch for them,
 *    so those rows are `pending` and undeliverable until the contract grows one.
 *    [SyncStore.pending] filters them out *before* the window is taken, so however many of them
 *    accumulate they can never fill it and stall the measurements behind them — FR-SYNC-007's
 *    "une mutation invalide ne bloque pas indéfiniment toutes les mutations suivantes", applied
 *    to a mutation the client rather than the server cannot take. `healthProfile` was in that
 *    set until `AGGREGATE_TYPES` named it; it drains now, like a weight.
 *
 * ## The cursor
 *
 * Opaque. It is read from storage, put in a request and written back from a response. It is
 * never parsed, never compared, never ordered by and never constructed. `sequence` arrives on
 * every change and is deliberately unused: it is the server's to assign (PRD 12.3), and a client
 * that reads it will eventually add one to it.
 */
class SyncEngine(
    private val store: SyncStore,
    private val api: SyncApi,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Where recovery runs. It is started in [init] rather than at the first [sync] so that
     * "engine start" means what it says: constructing the engine is what unsticks the outbox,
     * whether or not a synchronisation follows.
     */
    scope: CoroutineScope,
) {

    /**
     * The result of the recovery started in [init] — the [Result] and not the count, so a
     * recovery that failed can be told apart from one that found nothing and retried in [sync]
     * instead of being latched at zero for the life of the process.
     */
    private val recovery = CompletableDeferred<Result<Int>>()

    /**
     * One synchronisation at a time. `Sync now`, the periodic worker and the foreground trigger
     * of PRD 9.4 can all fire at once; two engines pushing the same batch would each mark it
     * `inflight`, and the loser would requeue rows the winner had already acknowledged.
     */
    private val gate = Mutex()

    init {
        scope.launch { recovery.complete(runCatching { store.requeueInflight() }) }
    }

    /**
     * Runs one full synchronisation and reports what happened.
     *
     * It never throws for a business or network reason: every one of those is a
     * [SyncOutcome]. It does propagate [CancellationException], because a cancelled worker must
     * die rather than report a result — and the `finally` blocks below have already put the
     * outbox back before it does.
     */
    suspend fun sync(): SyncOutcome = gate.withLock {
        // A recovery that failed at construction is retried here rather than latched at zero. A
        // row left `inflight` by a killed process holds a change that exists on the phone and
        // nowhere else, so swallowing the single attempt to unstick it would strand that change
        // for the whole life of the process. Retrying is one `UPDATE`, and it is idempotent: its
        // `WHERE state = 'inflight'` matches nothing once the rows are back.
        val recovered = recovery.await().getOrElse { store.requeueInflight() }

        val serverUrl = store.serverUrl()
        val deviceId = store.deviceId()
        if (serverUrl.isNullOrBlank() || deviceId.isNullOrBlank()) {
            // PRD 21: Mue stays entirely usable with no server configured. Not an error, not a
            // failure to record, and nothing to retry.
            return@withLock SyncOutcome.NotPaired
        }

        val push = try {
            push(deviceId)
        } catch (failure: SyncTransportException) {
            store.recordFailure(failure.code, failure.message)
            return@withLock SyncOutcome.Failed(failure.code, failure.message, failure.retryable)
        }

        pull(push, recovered)
    }

    // --- push -------------------------------------------------------------------------------

    private suspend fun push(deviceId: String): PushTally {
        // Counted before the window is taken and not inside the loop below, because
        // `store.pending` no longer returns these rows at all: they are of an aggregate type
        // this build has no wire branch for, and leaving them in the queue a send selects from
        // is what would eventually stall every measurement behind them (FR-SYNC-007).
        var deferred = store.deferredCount()

        val batch = store.pending(WIRE_PUSH_MAX_MUTATIONS)
        if (batch.isEmpty()) return PushTally(deferred = deferred)

        val origin = SyncWire.androidOrigin(deviceId)
        val sendable = LinkedHashMap<String, MutationEnvelopeDto>(batch.size)
        val byId = batch.associateBy { it.mutationId }
        var unreadable = 0

        for (mutation in batch) {
            val envelope = try {
                SyncWire.toEnvelope(mutation, origin)
            } catch (failure: SerializationException) {
                // A payload this build wrote and can no longer read is local corruption, not a
                // protocol event. FR-SYNC-007: keep it, mark it, and let the rest of the batch
                // go out — deleting it would lose a change the user made.
                unreadable++
                store.reject(
                    mutation.mutationId,
                    SyncErrorCodes.SYNC_INVALID_PAYLOAD,
                    failure.message ?: "the stored payload could not be read",
                )
                continue
            }

            if (envelope == null) {
                // A row of a sendable aggregate type that still has no wire shape — an `op` this
                // build does not recognise, which only a downgrade can produce. Left `pending`
                // for the same reason as the rows counted above: it loses nothing, it blocks
                // nothing now that the queue is selected by type, and marking it `failed` would
                // show the user a `Sync issue` for something they did not do wrong.
                deferred++
                continue
            }

            sendable[mutation.mutationId] = envelope
        }

        if (sendable.isEmpty()) return PushTally(deferred = deferred, unreadable = unreadable)

        val inflight = sendable.keys.toList()
        var settled = false
        store.markInflight(inflight)

        try {
            val response = api.push(PushRequestDto(sendable.values.toList()))
            val results = response.results.associateBy { it.mutationId }
            val serverTime = SyncWire.toEpochMillisOrNull(response.serverTime) ?: now()

            var applied = 0
            var duplicates = 0
            var rejected = 0
            val unanswered = mutableListOf<String>()

            for (mutationId in inflight) {
                val mutation = byId.getValue(mutationId)
                when (val result = results[mutationId]) {
                    is MutationAppliedDto -> {
                        applied++
                        store.acknowledge(mutation, SyncWire.counterOrNull(result.revision), serverTime)
                    }

                    // FR-SYNC-006: the server replaying a stored result. Identical handling to
                    // `applied` on purpose — a duplicate is the protocol working, and treating
                    // it as an error would make every lost response look like a fault.
                    is MutationDuplicateDto -> {
                        duplicates++
                        store.acknowledge(mutation, SyncWire.counterOrNull(result.revision), serverTime)
                    }

                    is MutationRejectedDto -> {
                        rejected++
                        store.reject(mutationId, result.error.code, result.error.message)
                    }

                    // The contract promises one result per submitted mutation. A server that
                    // does not keep that promise must not cost a change: the row goes back to
                    // `pending` and is sent again, which is safe because it is idempotent.
                    null -> unanswered += mutationId
                }
            }

            if (unanswered.isNotEmpty()) store.requeuePending(unanswered)
            settled = true
            return PushTally(applied, duplicates, rejected, deferred, unreadable)
        } finally {
            // Covers the throw, the cancellation and the bug alike. `NonCancellable` is what
            // makes it work in the case that matters: a worker stopped by the system unwinds
            // through here with its job already cancelled, and a plain suspend call would be
            // cancelled too and leave the rows `inflight`.
            if (!settled) withContext(NonCancellable) { store.requeuePending(inflight) }
        }
    }

    // --- pull -------------------------------------------------------------------------------

    private suspend fun pull(push: PushTally, recovered: Int): SyncOutcome {
        var pages = 0
        var changes = 0
        var hasMore = false

        while (pages < MAX_PAGES_PER_RUN) {
            val request = PullRequestDto(
                cursor = store.cursor(),
                limit = WIRE_PULL_DEFAULT_LIMIT,
                supportedSchemaVersions = SyncWire.SUPPORTED_SCHEMA_VERSIONS,
            )

            val response = try {
                api.pull(request)
            } catch (failure: SyncTransportException) {
                store.recordFailure(failure.code, failure.message)
                return SyncOutcome.Failed(failure.code, failure.message, failure.retryable)
            }

            when (response) {
                is PullUpgradeRequiredDto -> {
                    // PRD 12.4 and PRD 18: the cursor does not move, the local data is
                    // untouched, and the user is asked to update. Nothing here writes a cursor.
                    store.recordFailure(response.error.code, response.error.message)
                    return SyncOutcome.UpgradeRequired(response.error)
                }

                is PullPageDto -> {
                    val unapplicable = response.changes.firstOrNull { !canApply(it) }
                    if (unapplicable != null) {
                        // The server's own check passed — it had `supportedSchemaVersions` —
                        // and this build still cannot apply the change. Refusing the page here
                        // is the client half of PRD 12.4: the cursor stays where it is rather
                        // than skipping a change, so nothing is silently lost.
                        val error = MueErrorDto(
                            code = SyncErrorCodes.SYNC_UPGRADE_REQUIRED,
                            message = "This build cannot apply " +
                                "${unapplicable.aggregateType} at payload schema version " +
                                "${unapplicable.payloadSchemaVersion}. The cursor has not moved.",
                            retryable = false,
                            aggregateType = unapplicable.aggregateType,
                            aggregateId = unapplicable.aggregateId,
                        )
                        store.recordFailure(error.code, error.message)
                        return SyncOutcome.UpgradeRequired(error)
                    }

                    store.applyPage(response.changes, response.nextCursor, now())
                    changes += response.changes.size
                    pages++
                    hasMore = response.hasMore
                    if (!response.hasMore) break
                }
            }
        }

        return SyncOutcome.Completed(
            recovered = recovered,
            applied = push.applied,
            duplicates = push.duplicates,
            rejected = push.rejected,
            deferred = push.deferred,
            unreadable = push.unreadable,
            pages = pages,
            changes = changes,
            // True only when the page loop stopped on its own bound. The cursor is persisted, so
            // the next run resumes exactly where this one left off (FR-SYNC-003's idempotent
            // resumption), and the worker asks for one.
            moreAvailable = hasMore,
        )
    }

    /**
     * Whether this build can apply a change *before* its page is opened.
     *
     * Three ways it cannot, and each would be a silent loss if the cursor moved past it: an
     * aggregate type with no local table, a payload version this build does not implement, and a
     * revision the local column cannot hold.
     */
    private fun canApply(change: SyncChangeDto): Boolean {
        SyncWire.localAggregateType(change.aggregateType) ?: return false
        val supported = SyncWire.SUPPORTED_SCHEMA_VERSIONS[change.aggregateType] ?: return false
        if (change.payloadSchemaVersion !in supported) return false
        return SyncWire.counterOrNull(change.meta.revision) != null
    }

    private data class PushTally(
        val applied: Int = 0,
        val duplicates: Int = 0,
        val rejected: Int = 0,
        val deferred: Int = 0,
        val unreadable: Int = 0,
    )

    companion object {
        /**
         * How many pages one run will read before handing back.
         *
         * An initial synchronisation of a long history is many pages, and a worker that reads
         * them all in one go holds a wakelock for as long as the history is. Stopping is free:
         * the cursor is persisted after every page, so the next run continues rather than
         * restarts.
         */
        const val MAX_PAGES_PER_RUN: Int = 50
    }
}

/** What one run of [SyncEngine.sync] did. Every outcome is a value; none of them is a throw. */
sealed interface SyncOutcome {

    /** No server is paired. PRD 21: that is a supported way to use Mue, not a failure. */
    data object NotPaired : SyncOutcome

    data class Completed(
        /** Rows a previous process left `inflight` and this engine returned to the queue. */
        val recovered: Int,
        val applied: Int,
        /** FR-SYNC-006 replays. A number greater than zero means a response was lost, not lost data. */
        val duplicates: Int,
        /** FR-SYNC-007. Kept, marked, and surfaced as `Sync issue`. */
        val rejected: Int,
        /**
         * Journalled rows the contract has no wire branch for yet — the activity, custom
         * exercise and food aggregates of PRD 10.1. Still `pending`, never selected by a send,
         * and blocking nothing behind them.
         */
        val deferred: Int,
        /** Outbox rows whose stored payload could not be read back. Kept and marked. */
        val unreadable: Int,
        val pages: Int,
        val changes: Int,
        val moreAvailable: Boolean,
    ) : SyncOutcome {
        val hasIssues: Boolean get() = rejected > 0 || unreadable > 0
    }

    /** PRD 12.4: the cursor did not move and no local data was touched. */
    data class UpgradeRequired(val error: MueErrorDto) : SyncOutcome

    /** FR-SYNC-008: recorded, shown in `Data & sync`, and never a notification. */
    data class Failed(val code: String, val message: String, val retryable: Boolean) : SyncOutcome
}
