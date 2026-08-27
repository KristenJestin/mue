package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes

/**
 * What to do about an outbox row a **newer build would have written differently**.
 *
 * ## The queue an upgrade inherits
 *
 * `SyncOutbox` minted `UUID.randomUUID()` — a v4 — where `mutationIdSchema` is `z.uuidv7()`.
 * `MutationIds` fixed that, and fixed it only for rows minted *after* it. A contract fix that
 * changes how rows are **written** does nothing for rows already **stored**: every row already
 * sitting in an outbox keeps its v4 id, `submitMutations` in `packages/domain` refuses the whole
 * batch carrying it before it opens a transaction, and no retry can ever change the outcome. The
 * owner's phone held exactly one such row — his health profile — and `Data & sync` counted
 * `1 change waiting to be sent` that nothing could make fall.
 *
 * This is the same shape as `SyncDao.requeueInflight`: a queue can be left in a state that no
 * future run of the application can leave on its own. That is why the two run in the same place,
 * at engine start, one after the other.
 *
 * ## Why a repair pass and not a Room migration
 *
 * Three reasons, and the third is the one that generalises:
 *
 * 1. Nothing about the *schema* is wrong. `sync_mutations` has the right columns and the right
 *    types; it is the values in them that a newer build would write differently, so there is no
 *    version step to hang a migration on without inventing a no-op bump.
 * 2. SQLite cannot mint a UUIDv7. The identifier has to come from [MutationIds], so that what a
 *    repair writes and what a save writes are the same definition of what a mutation id is.
 * 3. A migration runs **once, per version step, forwards**. A repair predicated on the row's own
 *    defect runs whenever a defective row exists — including one restored from a backup taken by
 *    an older build, or written by a downgrade, which a migration would never see again.
 *
 * ## Which rows are safe to re-mint
 *
 * A mutation id is the idempotency key of FR-SYNC-006: the same id on every retry of the same
 * send is what makes the server replay its stored result instead of applying the change twice.
 * Changing an id the server has seen would double-apply a change. So the safety argument has to
 * be a *proof*, and it has two independent legs:
 *
 * - **The server cannot have accepted this row.** `submitMutations` reads every `mutationId` in
 *   a push *before* it opens any transaction and throws `sync.invalid_payload` for the batch if
 *   one of them is unreadable. So a row whose id is not a `mutationIdSchema` value has never
 *   reached `mutation_log`, has nothing recorded against it, and there is nothing to
 *   deduplicate against. This is the load-bearing leg, and note what carries it: *the invalid
 *   id is itself the evidence*. The row's state and its counters corroborate; they do not prove.
 * - **The row is not inside a request right now.** [STATE_INFLIGHT][SyncMutationEntity
 *   .STATE_INFLIGHT] is excluded outright. An `inflight` row is one whose envelope may already
 *   be serialised and on the wire; re-minting under it would have the response come back keyed
 *   by the old id, the engine count the mutation unanswered, and the row be sent a second time
 *   under the new one — the double-apply again, by a different route. `requeueInflight` runs
 *   first and returns the stranded ones to `pending`, where this pass then sees them, and that
 *   is safe for the same reason as everything else here: their ids were refused at the door.
 *
 * `attempt_count` deliberately does **not** carry the argument, though it is used below as a
 * corroborating guard. In this schema it is incremented by one statement only —
 * `SyncDao.markFailed` — so it counts *rejections*, not sends. A `pending` row at zero may still
 * have been received by a server whose response was then lost, and a `failed` row at three may
 * never have left the phone. Reading it as "how many times the server might have seen this"
 * would be reading a counter for something it does not count.
 */
object OutboxRepair {

    /**
     * The verdict on one stored row.
     *
     * Three values rather than a boolean, because "leave it alone" splits into two facts worth
     * telling apart: a row that is fine, and a row that is broken in a way this pass cannot mend.
     */
    enum class Verdict {
        /** The id is one `mutationIdSchema` accepts. Nothing to repair, whatever else is wrong. */
        SOUND,

        /** Re-mint the id and return the row to `pending`. */
        REMINT,

        /** Unsendable, and a re-mint would not make it sendable. Left exactly as it is. */
        HELD,
    }

    /**
     * The failures a new identifier cannot cure: every one of them names a defect in the
     * *payload* or in the *aggregate*, which a re-mint does not touch. Re-minting one and
     * returning it to the queue would send a row that is certain to be refused again, which is
     * precisely the churn FR-SYNC-007's `failed` state exists to prevent.
     *
     * `sync.invalid_payload` is deliberately **absent**, and that is the interesting entry.
     * It is the code the owner's row carries, and it is also the code this client stamps on a
     * row whose stored payload it can no longer read (`SyncEngine.push`). One code, two
     * unrelated causes — which is exactly why the curable/incurable test below is not made on
     * the code at all.
     */
    val INCURABLE_ERROR_CODES: Set<String> = setOf(
        SyncErrorCodes.SYNC_UNKNOWN_AGGREGATE_TYPE,
        SyncErrorCodes.SYNC_UPGRADE_REQUIRED,
        SyncErrorCodes.SYNC_MISSING_REQUIRED_FIELD,
        SyncErrorCodes.SYNC_REVISION_CONFLICT,
        SyncErrorCodes.SYNC_AGGREGATE_DELETED,
    )

    /**
     * Curable, or not, for one stored row.
     *
     * ## Telling a curable failure from an incurable one
     *
     * The test is **structural and local, not diagnostic**: *does this row carry the one defect
     * this pass removes?* An error code is a symptom, and `sync.invalid_payload` alone has at
     * least two causes that share it, so a repair that decided on the code would both move rows
     * it cannot help and skip the row it exists for. Asking the row whether its id is a
     * `mutationIdSchema` value asks about the cause instead, and the answer is a fact the phone
     * can check by itself with no server and no guesswork.
     *
     * What that leaves is a residue: a row whose id is bad *and* whose payload is also bad. It
     * is re-minted, requeued, and refused again the next send — locally, before a request is
     * even built — and marked `failed` with its true reason, which is an improvement on being
     * marked `failed` with a reason that was never the real one. It costs one iteration, once.
     *
     * ## And it can only happen once
     *
     * The pass is idempotent by construction, and not by remembering anything: its predicate is
     * "this id is not a mutation id", and its action is to make that false. A row it repairs is
     * never a candidate again, whatever becomes of it afterwards — so an incurable row is
     * requeued exactly once in the life of the database, and there is no loop to bound.
     */
    fun verdict(
        state: String,
        mutationId: String,
        attemptCount: Int,
        lastErrorCode: String?,
    ): Verdict {
        if (MutationIds.isMutationId(mutationId)) return Verdict.SOUND
        return when (state) {
            // Never. The row may be on the wire under this very id.
            SyncMutationEntity.STATE_INFLIGHT -> Verdict.HELD

            /*
             * The owner's row. `attempt_count` is a corroborating guard and not the argument —
             * see the class comment — and it is free here: nothing in this build can produce a
             * `pending` row with a non-zero count, since the only statement that increments it
             * also moves the row to `failed`. If some future one can, the row stays in the queue
             * and stays counted, which is a symptom already known to be readable rather than a
             * silent loss.
             */
            SyncMutationEntity.STATE_PENDING ->
                if (attemptCount == 0) Verdict.REMINT else Verdict.HELD

            /*
             * FR-SYNC-007 keeps a refused mutation out of the queue, and rightly: it holds a
             * change the user made and deleting it would lose it. But a row refused *for its
             * id* was never judged on its merits — the server threw the batch out before it
             * looked at the payload — so leaving it here would be leaving the owner's change
             * exactly where it was.
             */
            SyncMutationEntity.STATE_FAILED ->
                if (lastErrorCode in INCURABLE_ERROR_CODES) Verdict.HELD else Verdict.REMINT

            // A state no constant names — only a downgrade or a hand-edited database. Untouched.
            else -> Verdict.HELD
        }
    }
}
