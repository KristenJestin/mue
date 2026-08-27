package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRD 9.4's missing trigger, from the mint to the request.
 *
 * The defect these cover was found on the owner's own phone: he changed his date of birth with
 * the app in the foreground, the row landed in `sync_mutations` as `pending` with
 * `attempt_count` at zero, and nothing attempted it — `SyncScheduler.syncNow` had three callers
 * and none of them was a save. `Data & sync` read `Changes pending`, honestly, for as long as he
 * left the app open.
 *
 * Everything here runs on `runTest`'s virtual clock. The quiet window is a rule, not a duration
 * to be waited out, and asserting on it with a real `Thread.sleep` would make the suite slower
 * and flakier for no extra confidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushOnWriteTest {

    private val outbox = SyncOutbox(newMutationId = { "mutation" }, now = { 1_772_000_000_000L })

    /**
     * The exact gesture, on the exact aggregate. Changing a birth date must schedule a send, and
     * before [SyncOutbox.minted] existed it scheduled nothing at all.
     */
    @Test
    fun `changing the birth date schedules a send`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        outbox.healthProfileUpsert(heightCm = 178, birthDate = LocalDate.of(1998, 11, 18))
        advanceUntilIdle()

        assertEquals(1, scheduled, "a saved profile must schedule exactly one send")
        collector.cancelAndJoin()
    }

    /** The same for the aggregate the app is opened for. */
    @Test
    fun `saving a weight schedules a send`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        outbox.measurementUpsert(
            Measurement(LocalDate.of(2026, 8, 27), Weight.ofHundredthsClamped(7_845)),
        )
        advanceUntilIdle()

        assertEquals(1, scheduled)
        collector.cancelAndJoin()
    }

    /**
     * A recipe with forty ingredients, a measurement moved to another date, a meal plan
     * replaced: one gesture, several rows, milliseconds apart. Forty requests would be forty
     * WorkManager transactions and forty chances to cancel the run started by the first.
     */
    @Test
    fun `a burst of forty writes schedules one send`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        repeat(40) { index ->
            outbox.foodUpsert(food.copy(id = FoodId("food-$index")))
            advanceTimeBy(5)
        }
        advanceUntilIdle()

        assertEquals(1, scheduled, "the burst must collapse into a single request")
        collector.cancelAndJoin()
    }

    /**
     * The window is a *quiet* window and not a rate limit: it restarts at every signal, so a
     * burst that never pauses never sends until it does. Forty rows five milliseconds apart is
     * two hundred milliseconds of burst against a window of seven hundred and fifty, so nothing
     * has gone out while it is still arriving.
     */
    @Test
    fun `nothing is scheduled while the burst is still arriving`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        repeat(40) {
            outbox.measurementDelete(LocalDate.of(2026, 8, 27))
            advanceTimeBy(5)
        }

        assertEquals(0, scheduled, "the window must not close while writes are still landing")
        advanceUntilIdle()
        assertEquals(1, scheduled)
        collector.cancelAndJoin()
    }

    /** Two separate gestures are two separate sends; the collapsing must not swallow one. */
    @Test
    fun `two saves a quiet window apart schedule two sends`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        outbox.healthProfileUpsert(heightCm = 178, birthDate = null)
        advanceUntilIdle()
        outbox.healthProfileUpsert(heightCm = 179, birthDate = null)
        advanceUntilIdle()

        assertEquals(2, scheduled)
        collector.cancelAndJoin()
    }

    /** An app nobody writes in wakes nothing up. */
    @Test
    fun `an idle app schedules nothing`() = runTest {
        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }

        advanceTimeBy(PushOnWrite.QUIET_WINDOW_MILLIS * 100)
        advanceUntilIdle()

        assertEquals(0, scheduled)
        collector.cancelAndJoin()
    }

    /**
     * Every mint, and not a sample of them.
     *
     * This is the assertion that makes `SyncOutbox` the right hook rather than a convenient one:
     * whichever of the thirteen a repository calls, the send is scheduled, so a fourteenth added
     * next month inherits the trigger instead of needing one. Each is checked on its own so a
     * failure names the method that stopped announcing.
     */
    @Test
    fun `every aggregate the outbox mints announces itself`() = runTest {
        val mints: List<Pair<String, () -> Unit>> = listOf(
            "measurementUpsert" to {
                outbox.measurementUpsert(
                    Measurement(LocalDate.of(2026, 8, 27), Weight.ofHundredthsClamped(7_845)),
                )
                Unit
            },
            "measurementDelete" to { outbox.measurementDelete(LocalDate.of(2026, 8, 27)); Unit },
            "healthProfileUpsert" to { outbox.healthProfileUpsert(178, null); Unit },
            "foodUpsert" to { outbox.foodUpsert(food); Unit },
            "foodDelete" to { outbox.foodDelete(FoodId("food-1")); Unit },
            "recipeUpsert" to { outbox.recipeUpsert(recipe); Unit },
            "recipeDelete" to { outbox.recipeDelete(RecipeId("recipe-1")); Unit },
            "foodLogUpsert" to { outbox.foodLogUpsert(logEntry); Unit },
            "foodLogDelete" to { outbox.foodLogDelete(FoodLogEntryId("log-1")); Unit },
            "mealPlanUpsert" to { outbox.mealPlanUpsert(plan); Unit },
            "mealPlanDelete" to { outbox.mealPlanDelete(plan.key); Unit },
        )

        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        mints.forEachIndexed { index, (name, mint) ->
            mint()
            advanceUntilIdle()
            assertEquals(index + 1, scheduled, "$name minted a row and scheduled no send")
        }

        collector.cancelAndJoin()
    }

    /**
     * A save must never wait on a network decision, and the shape of the flow is what guarantees
     * it: `tryEmit` on a buffer of one that drops the oldest cannot suspend, cannot fail and
     * cannot fill up. Ten thousand mints with nobody listening return, and the tenth thousand is
     * as cheap as the first.
     *
     * Written as a test rather than as a comment because the guarantee is one constructor
     * argument away from being lost — `SUSPEND` is the default this deliberately is not.
     */
    @Test
    fun `minting never blocks and never accumulates when nobody is listening`() = runTest {
        repeat(10_000) { outbox.measurementDelete(LocalDate.of(2026, 8, 27)) }

        var scheduled = 0
        val collector = launch { pushOnWrite { scheduled++ }.run() }
        advanceUntilIdle()

        assertEquals(0, scheduled, "replay = 0: a late collector inherits no backlog")

        outbox.measurementDelete(LocalDate.of(2026, 8, 27))
        advanceUntilIdle()
        assertEquals(1, scheduled, "and it still hears the next one")
        collector.cancelAndJoin()
    }

    /** The window is a constant somebody will want to change; it must be the only one. */
    @Test
    fun `the quiet window is short enough to be invisible and long enough to collapse a save`() {
        assertTrue(
            PushOnWrite.QUIET_WINDOW_MILLIS in 200L..2_000L,
            "a window outside this range is either a rate limit or a race",
        )
    }

    /**
     * A scheduler that throws must cost one send and not the trigger.
     *
     * `collectLatest` lets an exception out of the collection, which would end the coroutine and
     * leave every later save journalled with nobody listening — the exact silent stop this class
     * exists to remove, reintroduced one level up.
     */
    @Test
    fun `a scheduler that throws does not stop the next write from scheduling`() = runTest {
        var scheduled = 0
        val collector = launch {
            PushOnWrite(
                minted = outbox.minted,
                schedule = {
                    scheduled++
                    if (scheduled == 1) error("WorkManager is not initialised")
                },
            ).run()
        }
        advanceUntilIdle()

        outbox.measurementDelete(LocalDate.of(2026, 8, 27))
        advanceUntilIdle()
        assertEquals(1, scheduled)

        outbox.measurementDelete(LocalDate.of(2026, 8, 28))
        advanceUntilIdle()
        assertEquals(2, scheduled, "the collector died with the first failure")

        collector.cancelAndJoin()
    }

    private fun pushOnWrite(schedule: () -> Unit) =
        PushOnWrite(minted = outbox.minted, schedule = schedule)

    private companion object {
        val food = Food(
            id = FoodId("food-1"),
            name = "Huile d'olive",
            source = FoodSource.CUSTOM,
            per100 = Nutrients(
                energy = assertNotNull(Energy.ofMilliKcalOrNull(899_000)),
                protein = assertNotNull(Macro.ofMilligramsOrNull(0)),
            ),
        )

        val recipe = RecipeDetail(
            recipe = Recipe(
                id = RecipeId("recipe-1"),
                name = "Dahl",
                type = RecipeType.MAIN,
                baseServings = 4,
                steps = listOf("Un", "Deux"),
            ),
            ingredients = listOf(
                RecipeIngredient(
                    id = RecipeIngredientId("ing-1"),
                    foodId = FoodId("food-1"),
                    quantity = assertNotNull(Quantity.ofThousandthsOrNull(250_000)),
                    unit = ReferenceUnit.GRAM,
                    position = 0,
                    foodName = "Lentilles corail",
                ),
            ),
        )

        val logEntry = FoodLogEntry(
            id = FoodLogEntryId("log-1"),
            consumedOn = LocalDate.of(2026, 8, 25),
            consumedAt = LocalTime.of(13, 5),
            slot = MealSlot.LUNCH,
            kind = FoodLogKind.FOOD,
            title = "Dahl",
            amount = LoggedAmount.Measured(
                assertNotNull(Quantity.ofThousandthsOrNull(180_000)),
                ReferenceUnit.GRAM,
            ),
            nutrients = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(320_000))),
            estimation = Estimation.MEASURED,
            sourceRef = "food-1",
        )

        val plan = MealPlanEntry(
            plannedOn = LocalDate.of(2026, 9, 1),
            slot = MealSlot.DINNER,
            recipeId = RecipeId("recipe-1"),
            plannedServings = assertNotNull(Servings.ofThousandthsOrNull(2_000)),
        )
    }
}

