package fr.kristenjestin.mue.ui.food.day

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = FoodDayPreviewData.TODAY
private val YESTERDAY: LocalDate = TODAY.minusDays(1)
private val DINNER_TODAY: MealPlanKey = MealPlanKey(TODAY, MealSlot.DINNER)

/** Mid-afternoon, so `MealSlotRules.defaultTime` has an hour of today to answer with. */
private val NOW: LocalDateTime = TODAY.atTime(15, 42)

/**
 * The `Day` screen's state and its two writes, with the four stores faked (PRD_FOOD 10 and 12).
 *
 * The repositories are interfaces and no implementation exists yet, which is the arrangement
 * this suite is built on: every rule below — the day the screen opens on, the day it refuses to
 * walk to, the frozen values of a confirmed proposal — is settled here, on the JVM, before an
 * emulator ever runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodDayViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region the day on screen (PRD_FOOD 10.1)

    @Test
    fun `the screen opens on today`() = dayTest { day ->
        assertEquals(TODAY, state(day).date)
        assertTrue(state(day).isToday)
    }

    @Test
    fun `stepping back moves one day and stepping forward comes back`() = dayTest { day ->
        day.viewModel.onPreviousDay()
        assertEquals(YESTERDAY, state(day).date)

        day.viewModel.onNextDay()
        assertEquals(TODAY, state(day).date)
    }

    /** PRD_FOOD 22: "un jour futur ne peut pas être complété". */
    @Test
    fun `the day after today is refused even when the control is pressed`() = dayTest { day ->
        day.viewModel.onNextDay()

        assertEquals(TODAY, state(day).date)
        assertFalse(state(day).canGoForward)
    }

    @Test
    fun `a day chosen from the calendar is taken, and a future one is not`() = dayTest { day ->
        day.viewModel.onDayPicked(TODAY.minusDays(30))
        assertEquals(TODAY.minusDays(30), state(day).date)

        day.viewModel.onDayPicked(TODAY.plusDays(3))
        assertEquals(TODAY, state(day).date)
    }

    @Test
    fun `the calendar opens and closes, and choosing a day closes it`() = dayTest { day ->
        assertFalse(state(day).isDatePickerVisible)

        day.viewModel.onShowDatePicker()
        assertTrue(state(day).isDatePickerVisible)

        day.viewModel.onDayPicked(YESTERDAY)
        assertFalse(state(day).isDatePickerVisible)
    }

    /**
     * The day is the screen's own saved state, not a route (see `FoodRoute`).
     *
     * A handle restored from a `Bundle` reopens on the day that was being looked at, without the
     * module's stack ever having carried it — which is the whole reason it is not a route.
     */
    @Test
    fun `the day being viewed survives a process death`() = dayTest(
        savedState = SavedStateHandle(
            mapOf(FoodDayViewModel.KEY_DATE to YESTERDAY.toString()),
        ),
    ) { day ->
        assertEquals(YESTERDAY, state(day).date)
        assertFalse(state(day).isToday)
    }

    /** A key written by another build costs a day, never the first frame. */
    @Test
    fun `an unreadable saved day opens on today`() = dayTest(
        savedState = SavedStateHandle(mapOf(FoodDayViewModel.KEY_DATE to "last tuesday")),
    ) { day ->
        assertEquals(TODAY, state(day).date)
    }

    @Test
    fun `going back to today is one call`() = dayTest { day ->
        day.viewModel.onPreviousDay()
        day.viewModel.onPreviousDay()
        day.viewModel.onToday()

        assertEquals(TODAY, state(day).date)
    }

    // endregion

    // region what the day reads

    @Test
    fun `the lines of the day reach their moments and the others do not`() = dayTest(
        entries = FoodDayPreviewData.entries(TODAY) + FoodDayPreviewData.breakfast(YESTERDAY),
    ) { day ->
        val state = state(day)

        assertEquals(4, state.entryCount)
        assertEquals(1, state.slot(MealSlot.BREAKFAST).entries.size)
        assertEquals(2, state.slot(MealSlot.LUNCH).entries.size)
        assertEquals(1, state.slot(MealSlot.SNACK).entries.size)
        assertTrue(state.slot(MealSlot.DINNER).isEmpty)
    }

    /** PRD_FOOD 10.1: stepping to another day reads that day and nothing of this one. */
    @Test
    fun `stepping to another day reads that day`() = dayTest(
        entries = FoodDayPreviewData.entries(TODAY) + FoodDayPreviewData.breakfast(YESTERDAY),
    ) { day ->
        day.viewModel.onPreviousDay()
        val state = state(day)

        assertEquals(YESTERDAY, state.date)
        assertEquals(1, state.entryCount)
        assertTrue(state.slot(MealSlot.SNACK).isEmpty)
    }

    /** PRD_FOOD 13.1 all the way to the string, through the ViewModel this time. */
    @Test
    fun `an unknown protein reaches the screen as a dash and never as a zero`() = dayTest(
        entries = listOf(FoodDayPreviewData.tiramisu()),
    ) { day ->
        val snack = state(day).slot(MealSlot.SNACK)

        assertEquals("≈ 420 kcal", snack.totalLabel)
        assertEquals("${FoodLabels.UNKNOWN} protein", snack.proteinLabel)
    }

    /** Nothing has been read yet, and the screen says so rather than showing an empty journal. */
    @Test
    fun `the first state is a loading one on today`() = dayTest(
        entries = listOf(FoodDayPreviewData.breakfast()),
        subscribe = false,
    ) { day ->
        val first = day.viewModel.uiState.value

        assertTrue(first.isLoading)
        assertEquals(TODAY, first.date)
        assertEquals(0, first.entryCount)
    }

    // endregion

    // region proposals (PRD_FOOD 12)

    @Test
    fun `an unconfirmed proposal is named after its recipe`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
    ) { day ->
        val plan = state(day).slot(MealSlot.DINNER).plan

        assertNotNull(plan)
        assertEquals(FoodDayPreviewData.PLANNED_RECIPE, plan.recipeName)
    }

    /**
     * `I ate this` writes the line PRD_FOOD 12 asks for, values and all, and links the two.
     *
     * The nutrients are the recipe's, computed once through `NutritionMath` and frozen on the
     * line: 200 g of salmon at 200 kcal and 20 g of protein per 100 g make 400 kcal and 40 g for
     * the dish, halved by its two base servings, then multiplied by the 1.5 servings the proposal
     * carries — 300 kcal and 30 g.
     */
    @Test
    fun `I ate this creates the line and puts the proposal behind it`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
        foods = listOf(salmon()),
    ) { day ->
        day.viewModel.onConfirmPlan(DINNER_TODAY)
        advanceUntilIdle()

        val line = day.logs.saved.single()
        assertEquals(FoodLogKind.RECIPE, line.kind)
        assertEquals(FoodDayPreviewData.PLANNED_RECIPE, line.title)
        assertEquals(TODAY, line.consumedOn)
        assertEquals(MealSlot.DINNER, line.slot)
        assertEquals(Energy.ofKilocaloriesOrNull(300.0), line.nutrients.energy)
        assertEquals(Macro.ofGramsOrNull(30.0), line.nutrients.protein)
        assertEquals(Estimation.APPROXIMATE, line.estimation)
        assertEquals(DINNER_TODAY, line.fromPlan)
        assertEquals("1.5 × serving", line.amountLabel)
        assertEquals(LoggedAmount.Portioned(plannedServings()), line.amount)
        assertEquals(FoodDayPreviewData.PLANNED_RECIPE_ID.value, line.sourceRef)

        val link = day.plans.consumed.single()
        assertEquals(DINNER_TODAY, link.first)
        assertEquals(line.id, link.second)
    }

    /**
     * PRD_FOOD 10.3: a line confirmed on today takes the clock; one confirmed on a past day
     * takes the middle of its moment, because the current time would place last Tuesday's dinner
     * at whatever hour it happens to be now.
     */
    @Test
    fun `a proposal confirmed on a past day is timed from its moment, not from the clock`() =
        dayTest(
            plans = FoodDayPreviewData.plans(YESTERDAY),
            recipes = listOf(salmonDetail()),
            foods = listOf(salmon()),
            savedState = SavedStateHandle(
                mapOf(FoodDayViewModel.KEY_DATE to YESTERDAY.toString()),
            ),
        ) { day ->
            day.viewModel.onConfirmPlan(MealPlanKey(YESTERDAY, MealSlot.DINNER))
            advanceUntilIdle()

            assertEquals(MealSlot.DINNER.defaultTime, day.logs.saved.single().consumedAt)
        }

    @Test
    fun `a proposal confirmed today is timed from the clock`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
        foods = listOf(salmon()),
    ) { day ->
        day.viewModel.onConfirmPlan(DINNER_TODAY)
        advanceUntilIdle()

        assertEquals(NOW.toLocalTime(), day.logs.saved.single().consumedAt)
    }

    /** PRD_FOOD 10.1: once confirmed the card is gone, and the line it made is there instead. */
    @Test
    fun `a confirmed proposal leaves the moment to its line`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
        foods = listOf(salmon()),
    ) { day ->
        day.viewModel.onConfirmPlan(DINNER_TODAY)

        val dinner = state(day).slot(MealSlot.DINNER)
        assertNull(dinner.plan)
        assertEquals(1, dinner.entries.size)
    }

    /**
     * An ingredient whose food is gone makes the line unknown rather than zero.
     *
     * `NutritionMath.ingredientContribution` already decides that; what is proved here is that
     * the screen saves the line all the same, with nothing where nothing is known.
     */
    @Test
    fun `a recipe whose food is missing is confirmed with unknown values`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
        foods = emptyList(),
    ) { day ->
        day.viewModel.onConfirmPlan(DINNER_TODAY)
        advanceUntilIdle()

        val line = day.logs.saved.single()
        assertEquals(Nutrients.UNKNOWN, line.nutrients)
        assertNull(line.nutrients.energy)
    }

    @Test
    fun `confirming twice writes one line`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
        foods = listOf(salmon()),
    ) { day ->
        day.viewModel.onConfirmPlan(DINNER_TODAY)
        advanceUntilIdle()
        day.viewModel.onConfirmPlan(DINNER_TODAY)
        advanceUntilIdle()

        assertEquals(1, day.logs.saved.size)
    }

    /** `Dismiss` frees the moment and touches neither the recipe nor the journal. */
    @Test
    fun `dismiss frees the moment and writes no line`() = dayTest(
        plans = FoodDayPreviewData.plans(),
        recipes = listOf(salmonDetail()),
    ) { day ->
        day.viewModel.onDismissPlan(DINNER_TODAY)
        advanceUntilIdle()

        assertEquals(listOf(DINNER_TODAY), day.plans.deleted)
        assertTrue(day.logs.saved.isEmpty())
        assertNull(state(day).slot(MealSlot.DINNER).plan)
    }

    // endregion

    // region harness

    /** The ViewModel and the two stores a test needs to look inside. */
    private class Day(
        val viewModel: FoodDayViewModel,
        val logs: FakeFoodLogRepository,
        val plans: FakeMealPlanRepository,
    )

    /**
     * Subscribes the state before the body runs, because `WhileSubscribed` only reads the stores
     * while something is listening — exactly as the screen does. [subscribe] is false for the one
     * test that is about the state before anything has been read at all.
     */
    private fun dayTest(
        entries: List<FoodLogEntry> = emptyList(),
        plans: List<MealPlanEntry> = emptyList(),
        recipes: List<RecipeDetail> = emptyList(),
        foods: List<Food> = emptyList(),
        savedState: SavedStateHandle = SavedStateHandle(),
        subscribe: Boolean = true,
        body: suspend TestScope.(Day) -> Unit,
    ) = runTest(mainDispatcher) {
        val logRepository = FakeFoodLogRepository(entries)
        val planRepository = FakeMealPlanRepository(plans)
        val day = Day(
            viewModel = FoodDayViewModel(
                logs = logRepository,
                plans = planRepository,
                recipes = FakeRecipeRepository(recipes),
                foods = FakeFoodCatalogueRepository(foods),
                savedStateHandle = savedState,
                clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                locale = { Locale.UK },
            ),
            logs = logRepository,
            plans = planRepository,
        )

        val collector = if (subscribe) launch { day.viewModel.uiState.collect { } } else null
        advanceUntilIdle()

        body(day)

        collector?.cancel()
    }

    /** The state once every pending emission has landed. */
    private fun TestScope.state(day: Day): FoodDayUiState {
        advanceUntilIdle()
        return day.viewModel.uiState.value
    }

    private fun FoodDayUiState.slot(slot: MealSlot): FoodDaySlotUiState =
        slots.first { it.slot == slot }

    private fun plannedServings() = FoodDayPreviewData.plannedDinner().plannedServings

    private fun salmon(): Food = Food(
        id = FoodId("food-salmon"),
        name = "Salmon fillet",
        source = FoodSource.CIQUAL,
        per100 = Nutrients(
            energy = Energy.ofPer100OrNull(200.0),
            protein = Macro.ofPer100OrNull(20.0),
        ),
    )

    /** Two base servings of 200 g of salmon: 400 kcal and 40 g of protein in the whole dish. */
    private fun salmonDetail(): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = FoodDayPreviewData.PLANNED_RECIPE_ID,
            name = FoodDayPreviewData.PLANNED_RECIPE,
            type = RecipeType.MAIN,
            baseServings = 2,
        ),
        ingredients = listOf(
            RecipeIngredient(
                id = RecipeIngredientId("ingredient-salmon"),
                foodId = FoodId("food-salmon"),
                quantity = requireNotNull(Quantity.ofIngredientOrNull(200.0)),
                unit = ReferenceUnit.GRAM,
                position = 0,
            ),
        ),
    )

    // endregion
}
