package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.MealPlanKey

/**
 * What to do about a stored row whose **aggregate identifier** a newer build would have spelled
 * differently.
 *
 * ## The damage
 *
 * `MealPlanKey.aggregateId` was `"$plannedOn/${slot.id}"`. `aggregateIdSchema` in
 * `packages/contracts` is `^[A-Za-z0-9._:-]+$`, and `/` has never been in it. Every proposal
 * saved on this phone was journalled under that spelling — into `sync_mutations` and into
 * `sync_aggregate_state` — and none of it was ever noticed, because `mealPlanEntry` was not in
 * `AGGREGATE_TYPES` either: `SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES` filtered those rows out of
 * every send, so they sat `pending` and were never judged.
 *
 * The day the aggregate joined the contract, every one of them would have been refused **at the
 * envelope** — `mutationEnvelopeSchema` parses `aggregateId` before any handler runs — and marked
 * `failed` with `sync.invalid_payload`. A week of meal plans, all showing `Sync issue`, none of
 * them repairable by retrying, because retrying sends the same identifier.
 *
 * This is the same shape as [OutboxRepair] and it runs in the same place, for the same reason: a
 * queue can be left in a state that no future run of the application can leave on its own. A
 * contract change repairs how rows are *written*; it does nothing for rows already *stored*.
 *
 * ## Why rewriting these identifiers is safe, and provably so
 *
 * An aggregate identifier is not a mutation identifier, and rewriting one is a graver thing: the
 * mutation id is only an idempotency key, while the aggregate id says *which row this is about*.
 * Changing it on a row the server had accepted would fork the aggregate — the old identifier
 * would keep the server's revision and the new one would start again at a creation.
 *
 * That cannot have happened, and the proof does not rest on the state of any row:
 *
 * - **`mealPlanEntry` has never been in [SENDABLE_LOCAL_AGGREGATE_TYPES]**. `SyncStore.pending`
 *   selects by type in SQL, so no row of this type has ever been in a batch, in any build, on any
 *   device. There is therefore no `mutation_log` entry, no server revision and no journal position
 *   naming the old identifier — nothing anywhere to fork away from.
 * - **`sync_aggregate_state.revision` is null for every one of them**, for the same reason: that
 *   column is only ever written from a server acknowledgement or from an applied server change,
 *   and neither has ever happened for this type. So the identifier being rewritten carries no
 *   server state at all; it is a purely local name for a purely local row.
 *
 * The first leg is the load-bearing one and it is structural rather than observational: it is a
 * fact about the code that has run, not about the rows that happen to be present. [verdict]
 * corroborates it per row by refusing to touch anything `inflight` — which cannot occur for this
 * type, and costs one comparison to rule out anyway.
 *
 * ## What is not repaired, and why it does not need to be
 *
 * `food_log_entry` stores a proposal reference as two columns, `planned_on` and `plan_slot`, so
 * no composite string is persisted there. The `fromPlan` field of a journalled
 * `FoodLogEntryPayload` *is* a composite, and it is left exactly as it was: `SyncWire` decomposes
 * it into a date and a slot at envelope time through `MealPlanKey.parseOrNull`, which accepts
 * both spellings, so an old payload crosses the wire correctly without being rewritten. Repairing
 * a payload would mean decoding, editing and re-encoding a value the user wrote, and
 * FR-SYNC-007's rule against touching local data to fix a protocol problem applies to that even
 * when it would work.
 */
object MealPlanIdRepair {

    /**
     * The verdict on one stored row.
     *
     * Three values rather than a boolean, for the reason [OutboxRepair.Verdict] gives: "leave it
     * alone" splits into a row that is already correct and a row this pass must not touch.
     */
    enum class Verdict {
        /** The identifier is one `aggregateIdSchema` accepts. Nothing to do. */
        SOUND,

        /** Rewrite the identifier to the spelling a current build would have written. */
        RENAME,

        /** Not this pass's business, or on the wire right now. Left exactly as it is. */
        HELD,
    }

    /**
     * Curable, or not, for one stored row.
     *
     * The test is **structural and local**, exactly as `OutboxRepair.verdict`'s is: *does this row
     * carry the one defect this pass removes?* — an aggregate type of `mealPlanEntry` and an
     * identifier that is not the one [MealPlanKey.aggregateId] would write today. It asks nothing
     * about error codes, which describe symptoms, and nothing about attempt counts, which count
     * rejections rather than sends.
     *
     * It is idempotent by construction and not by remembering: the predicate is "this identifier
     * is not the canonical one", and the action makes that false. A row it repairs is never a
     * candidate again.
     */
    fun verdict(aggregateType: String, aggregateId: String, state: String): Verdict {
        if (aggregateType != FOOD_MEAL_PLAN_TYPE) return Verdict.HELD
        // Never. A row on the wire may already be serialised under the identifier it holds —
        // impossible for this type today, and refused here rather than argued about later.
        if (state == SyncMutationEntity.STATE_INFLIGHT) return Verdict.HELD
        return if (MealPlanKey.canonicalOrNull(aggregateId) == null) Verdict.SOUND else Verdict.RENAME
    }

    /** The identifier a repaired row takes, or null when the row is already sound. */
    fun canonicalOrNull(aggregateId: String): String? = MealPlanKey.canonicalOrNull(aggregateId)

    /**
     * `FoodAggregates.TYPE_MEAL_PLAN_ENTRY`, named here rather than imported.
     *
     * The constant this pass matches on is the value *already written into the database*, and
     * that value is frozen: it is what is in `sync_mutations.aggregate_type` on the owner's
     * phone. Binding it to a symbol another module could rename would make the pass silently stop
     * finding the rows it exists for.
     */
    const val FOOD_MEAL_PLAN_TYPE: String = "mealPlanEntry"
}
