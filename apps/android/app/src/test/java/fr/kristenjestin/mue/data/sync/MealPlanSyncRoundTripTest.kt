package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.toDomainOrNull
import fr.kristenjestin.mue.data.remote.sync.MealPlanEntryPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Servings
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val TUESDAY: LocalDate = LocalDate.parse("2026-09-01")

/**
 * A proposal posed on one phone, as the other phone receives it (PRD_FOOD 21.2 and 21.3).
 *
 * The two halves of one journey, and nothing between them is mocked: `SyncOutbox.mealPlanUpsert`
 * is what a `Plan this meal` writes into the queue, and `SyncWire.mealPlanEntryEntity` is what
 * `RoomSyncStore` applies from a pull. Holding them together is the only way to see that the
 * aggregate a device *sends* is the row another device *stores* — the two mappers live in
 * different packages and neither one's own test can notice a disagreement.
 *
 * ## Why convergence is structural here and not a merge rule
 *
 * PRD_FOOD 21.3 resolves two concurrent proposals by "la dernière mutation acceptée ; la
 * précédente est remplacée, jamais dupliquée", and that only works because both devices address
 * the **same aggregate id**. The id is `(date, moment)` — PRD_FOOD 8.5's own identity, with no
 * UUID to diverge — so a dinner planned on two phones offline is one row twice written, not two
 * rows to reconcile. The table's primary key is that same pair and its upsert is `REPLACE`, so
 * even a client that got the rule wrong could not produce a duplicate.
 */
class MealPlanSyncRoundTripTest {

    private val outbox = SyncOutbox()

    private val proposal = MealPlanEntry(
        plannedOn = TUESDAY,
        slot = MealSlot.DINNER,
        recipeId = RecipeId("recipe-7"),
        plannedServings = requireNotNull(Servings.ofConsumedOrNull(1.5)),
    )

    /**
     * What one phone sends is what the other stores, field for field.
     *
     * The date and the moment survive as the row's own key, so the entity the pull writes lands
     * exactly where the sending device's row is — which is what "converges" means for an
     * aggregate whose identity is its position in the week.
     */
    @Test
    fun `a proposal sent by one phone is the same row on the phone that receives it`() {
        val received = SyncWire.mealPlanEntryEntity(payloadOf(proposal), at = 1_700_000_000_000L)

        assertEquals(TUESDAY.toString(), received.plannedOn)
        assertEquals(MealSlot.DINNER.id, received.slot)

        val restored = assertNotNull(received.toDomainOrNull())
        assertEquals(proposal, restored)
        assertEquals(proposal.key, restored.key)
    }

    /**
     * The identifier the envelope carries, in the alphabet `aggregateIdSchema` accepts.
     *
     * A slash is not in it. Every proposal written before the separator changed was journalled
     * with one, filtered out of every send, and would have been refused *at the envelope* the day
     * `mealPlanEntry` joined the contract — so this is worth pinning on the very call the planning
     * sheet makes rather than only on `MealPlanKey`.
     */
    @Test
    fun `the mutation a planned meal enqueues is addressed by date and moment, with no slash`() {
        val mutation = outbox.mealPlanUpsert(proposal)

        assertEquals(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, mutation.aggregateType)
        assertEquals("2026-09-01:dinner", mutation.aggregateId)
        assertFalse(mutation.aggregateId.contains('/'))
        assertEquals(SyncMutationEntity.OP_UPSERT, mutation.op)
        assertEquals(proposal.key, MealPlanKey.parseOrNull(mutation.aggregateId))
    }

    /**
     * Two devices proposing for the same moment address one aggregate, never two.
     *
     * Different dishes, different serving counts, same `(date, moment)` — so the server has one
     * row to apply the last accepted mutation to, and PRD_FOOD 21.3 has a winner to name instead
     * of a merge to invent.
     */
    @Test
    fun `two devices planning the same moment write one aggregate id`() {
        val otherPhone = proposal.copy(
            recipeId = RecipeId("recipe-9"),
            plannedServings = Servings.ONE,
        )

        assertEquals(
            outbox.mealPlanUpsert(proposal).aggregateId,
            outbox.mealPlanUpsert(otherPhone).aggregateId,
        )
    }

    /** `Dismiss` names the same aggregate, so a tombstone lands on the row it means. */
    @Test
    fun `retiring a proposal addresses the row that holds it`() {
        val deletion = outbox.mealPlanDelete(proposal.key)

        assertEquals(outbox.mealPlanUpsert(proposal).aggregateId, deletion.aggregateId)
        assertEquals(SyncMutationEntity.OP_DELETE, deletion.op)
        assertNull(deletion.payload)
    }

    /**
     * The payload as it crosses the network: written by the local serializer, read by the wire
     * one.
     *
     * Two `@Serializable` classes with the same field names describe this aggregate — one in
     * `data/sync`, one in `data/remote/sync` — and nothing but a test holds them to the same
     * spelling. A field renamed on one side would silently drop to its default on the other, and
     * `consumedLogEntryId` defaulting to null is exactly how a confirmed proposal would come back
     * unconfirmed.
     */
    private fun payloadOf(entry: MealPlanEntry): MealPlanEntryPayloadV1Dto {
        val sent = Json.encodeToString(
            MealPlanEntryPayload.serializer(),
            MealPlanEntryPayload(
                plannedOn = entry.plannedOn.toString(),
                slot = entry.slot.id,
                recipeId = entry.recipeId.value,
                plannedServingsThousandths = entry.plannedServings.thousandths,
                consumedLogEntryId = entry.consumedLogEntryId?.value,
            ),
        )
        return Json.decodeFromString(MealPlanEntryPayloadV1Dto.serializer(), sent)
    }
}
