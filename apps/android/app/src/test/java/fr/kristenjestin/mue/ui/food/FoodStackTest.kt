package fr.kristenjestin.mue.ui.food

import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealSlot
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two movements of PRD_FOOD 7, and the difference between them.
 *
 * A **view** is a sibling and is *selected*: it replaces the root and takes any open sheet with
 * it. A **sheet** is *pushed*: it rises over whatever is showing and leaves it underneath. The
 * second defect the owner reported was one written as the other — `Use a recipe` selected the
 * `Recipes` view, so the sheet he was three taps into vanished and he landed on a browsing screen
 * with the switcher and the bottom bar back.
 *
 * These are cheap assertions about a small class, and they are here because the mistake they
 * catch is invisible in any single screen's test: both moves compile, both draw something, and
 * only the stack knows which one kept the thread.
 */
class FoodStackTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 27)

    // region a sheet keeps what is under it

    @Test
    fun `opening the recipe picker keeps the add sheet underneath`() {
        val stack = stack()
        stack.push(FoodRoute.AddFood(date = today, slot = MealSlot.DINNER))

        stack.push(FoodRoute.RecipePicker)

        assertEquals(FoodRoute.RecipePicker, stack.current)
        assertEquals(3, stack.entries.size)
        assertTrue(stack.entries.any { it is FoodRoute.AddFood }, "the sheet was left behind")
    }

    /** Leaving the picker returns to the meal being logged, aimed at the same moment. */
    @Test
    fun `leaving the recipe picker returns to the sheet it was opened from`() {
        val stack = stack()
        val sheet = FoodRoute.AddFood(date = today, slot = MealSlot.DINNER)
        stack.push(sheet)
        stack.push(FoodRoute.RecipePicker)

        stack.back()

        assertEquals(sheet, stack.current)
    }

    /**
     * The shape of the old defect, stated once so nobody rewrites it.
     *
     * Selecting a view is not a way to hand something over: it empties the stack down to one
     * entry, and everything the person was in the middle of goes with it.
     */
    @Test
    fun `selecting a view drops every open sheet`() {
        val stack = stack()
        stack.push(FoodRoute.AddFood(date = today, slot = MealSlot.DINNER))
        stack.push(FoodRoute.RecipePicker)

        stack.select(FoodRoute.Recipes)

        assertEquals(listOf<FoodRoute>(FoodRoute.Recipes), stack.entries)
    }

    /** A recipe written from the picker returns to a picker that now has something in it. */
    @Test
    fun `creating a recipe from the picker comes back to the picker`() {
        val stack = stack()
        stack.push(FoodRoute.AddFood())
        stack.push(FoodRoute.RecipePicker)
        stack.push(FoodRoute.RecipeEditor())

        stack.pop()

        assertEquals(FoodRoute.RecipePicker, stack.current)
    }

    // endregion

    // region the key it crosses a Bundle as

    @Test
    fun `the recipe picker survives a process death`() {
        assertEquals(FoodRoute.RecipePicker, FoodRoute.fromKey(FoodRoute.RecipePicker.key))
    }

    /**
     * Its key must not be read as one of the three recipe screens, which start with the same
     * word. `recipeDetail:` and `recipeEditor` are prefixes this one has to stay clear of.
     */
    @Test
    fun `the recipe picker key is not confused with the recipe screens`() {
        val keys = listOf(
            FoodRoute.RecipePicker,
            FoodRoute.RecipeEditor(),
            FoodRoute.FoodPicker,
        ).map(FoodRoute::key)

        assertEquals(keys.distinct(), keys)
        keys.forEach { key -> assertEquals(key, FoodRoute.fromKey(key).key) }
    }

    // endregion

    // region the day travels without a moment (the `Day` screen's one add action)

    /**
     * The shape the `Day` screen's one action pushes: a day, and no moment.
     *
     * It has to survive a `Bundle` as itself. Written as the bare `addFood` key — which is what a
     * date-only route used to produce — it came back aimed at **today**, so a process death while
     * logging Tuesday's supper would have moved the line to Wednesday without a word.
     */
    @Test
    fun `a sheet aimed at a day and no moment survives a process death`() {
        val sheet = FoodRoute.AddFood(date = today.minusDays(3))

        val restored = FoodRoute.fromKey(sheet.key)

        assertEquals(sheet, restored)
        assertEquals(today.minusDays(3), (restored as FoodRoute.AddFood).date)
        assertNull(restored.slot, "the hour decides the moment, not a saved key")
    }

    /** The three shapes write three different keys, and none of them reads as another. */
    @Test
    fun `a day, a day with a moment and a correction keep their keys apart`() {
        val keys = listOf(
            FoodRoute.AddFood(),
            FoodRoute.AddFood(date = today),
            FoodRoute.AddFood(date = today, slot = MealSlot.DINNER),
            FoodRoute.AddFood(entryId = FoodLogEntryId("an-entry")),
        )

        assertEquals(keys.map(FoodRoute::key).distinct().size, keys.size)
        keys.forEach { route -> assertEquals(route, FoodRoute.fromKey(route.key)) }
    }

    /** A key another build wrote is a day to forget, never a crash on the first frame. */
    @Test
    fun `an unreadable target degrades to a plain sheet`() {
        val restored = FoodRoute.fromKey("addFood:not-a-day")

        assertEquals(FoodRoute.AddFood(), restored)
    }

    // endregion

    private fun stack(): FoodStack = FoodStack(listOf(FoodRoute.Day))
}
