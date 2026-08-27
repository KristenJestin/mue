package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.FoodAggregates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The repair pass for aggregate identifiers a newer build spells differently.
 *
 * The rule is a pure function, tested here with no database, for the reason `OutboxRepair` is:
 * "which rows may be rewritten" is the load-bearing decision and it has to be readable and
 * provable on its own. What SQL does with the verdict is `RoomSyncStore.repairMealPlanIdentifiers`
 * and is proved on a device.
 */
class MealPlanIdRepairTest {

    private val legacy = "2026-09-01/dinner"
    private val canonical = "2026-09-01:dinner"

    /**
     * The row this pass exists for: a proposal journalled under the separator
     * `aggregateIdSchema` has never accepted.
     */
    @Test
    fun `a proposal journalled with a slash is renamed`() {
        assertEquals(
            MealPlanIdRepair.Verdict.RENAME,
            MealPlanIdRepair.verdict(
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = legacy,
                state = SyncMutationEntity.STATE_PENDING,
            ),
        )
        assertEquals(canonical, MealPlanIdRepair.canonicalOrNull(legacy))
    }

    /**
     * A `failed` row is repaired too.
     *
     * FR-SYNC-007 keeps a refused mutation out of the queue, and rightly — it holds a change the
     * user made. But a row refused *for its identifier* was never judged on its merits: the server
     * threw it out at the envelope, before it looked at the payload. Leaving it would leave the
     * user's proposal exactly where it was.
     */
    @Test
    fun `a row already refused for its identifier is repaired rather than left`() {
        assertEquals(
            MealPlanIdRepair.Verdict.RENAME,
            MealPlanIdRepair.verdict(
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = legacy,
                state = SyncMutationEntity.STATE_FAILED,
            ),
        )
    }

    /** Never a row that may already be serialised and on the wire under the name it holds. */
    @Test
    fun `an inflight row is never touched`() {
        assertEquals(
            MealPlanIdRepair.Verdict.HELD,
            MealPlanIdRepair.verdict(
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = legacy,
                state = SyncMutationEntity.STATE_INFLIGHT,
            ),
        )
    }

    /**
     * Idempotent by construction rather than by remembering: the predicate is "this identifier is
     * not the canonical one", and the action makes that false. A repaired row is never a candidate
     * again, and there is no loop to bound.
     */
    @Test
    fun `a row a current build wrote is sound, so the pass can run at every start`() {
        assertEquals(
            MealPlanIdRepair.Verdict.SOUND,
            MealPlanIdRepair.verdict(
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = canonical,
                state = SyncMutationEntity.STATE_PENDING,
            ),
        )
        assertNull(MealPlanIdRepair.canonicalOrNull(canonical))
    }

    /**
     * The pass is about one aggregate and touches nothing else.
     *
     * A measurement's identifier is a date and a session's is a UUID; both contain characters this
     * pass would never rewrite anyway, and the type check is what makes that a fact rather than a
     * coincidence of the identifiers that happen to be stored.
     */
    @Test
    fun `no other aggregate is this pass's business`() {
        for (type in listOf(
            SyncAggregateStateEntity.TYPE_MEASUREMENT,
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
            SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
            FoodAggregates.TYPE_FOOD,
            FoodAggregates.TYPE_RECIPE,
            FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        )) {
            assertEquals(
                MealPlanIdRepair.Verdict.HELD,
                MealPlanIdRepair.verdict(type, legacy, SyncMutationEntity.STATE_PENDING),
                "$type is not a meal plan",
            )
        }
    }

    /**
     * An identifier this pass cannot read is left alone rather than guessed at.
     *
     * It will be refused by the server with a message naming it, which is more useful than a
     * repair inventing a value for a row it does not understand.
     */
    @Test
    fun `an unreadable identifier is held, not invented`() {
        assertEquals(
            MealPlanIdRepair.Verdict.SOUND,
            MealPlanIdRepair.verdict(
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = "not-a-date/dinner",
                state = SyncMutationEntity.STATE_PENDING,
            ),
        )
        assertNull(MealPlanIdRepair.canonicalOrNull("not-a-date/dinner"))
    }

    /**
     * The constant is matched against what is *already in the database*, so it is spelled out
     * here rather than imported — and asserted equal to the symbol, so the two cannot part.
     */
    @Test
    fun `the type this pass matches is the one the rows carry`() {
        assertEquals("mealPlanEntry", MealPlanIdRepair.FOOD_MEAL_PLAN_TYPE)
        assertEquals(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, MealPlanIdRepair.FOOD_MEAL_PLAN_TYPE)
    }
}
