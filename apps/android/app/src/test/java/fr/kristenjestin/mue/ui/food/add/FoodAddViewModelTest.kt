package fr.kristenjestin.mue.ui.food.add

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.ui.food.day.FakeFoodLogRepository
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
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = FoodAddPreviewData.TODAY
private val YESTERDAY: LocalDate = TODAY.minusDays(1)

/** 19:40, which PRD_FOOD 10.3 puts in dinner — so a default that follows the clock is visible. */
private val NOW: LocalDateTime = TODAY.atTime(FoodAddPreviewData.NOW)

/**
 * The add flow driven end to end, with the journal and the catalogue faked (PRD_FOOD 7 to 15).
 *
 * Everything the sheet writes is proved here rather than on a device: the moment a line lands in,
 * the hour it carries, the values frozen on it, the refusals that stop it, and the draft that
 * survives a process death. The two Room repositories are covered where they belong, against a
 * real database, in `data/repository`'s own suites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodAddViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region opening the sheet (PRD_FOOD 7 and 10.3)

    @Test
    fun `a plus pressed inside a moment opens on that moment and that day`() = addTest { add ->
        add.viewModel.start(YESTERDAY, MealSlot.BREAKFAST, null)

        val state = state(add)
        assertEquals(YESTERDAY, state.date)
        assertEquals(MealSlot.BREAKFAST, state.slot)
        // PRD_FOOD 10.3: a retroactive line opens in the middle of its moment, not at the clock.
        assertEquals(MealSlot.BREAKFAST.defaultTime, state.time)
        assertEquals(FoodAddStage.PATHS, state.stage)
    }

    /** FR-FOOD-007: with nothing carried in, the clock preselects the moment. */
    @Test
    fun `opened from nowhere, the sheet follows the clock`() = addTest { add ->
        add.viewModel.start(null, null, null)

        val state = state(add)
        assertEquals(TODAY, state.date)
        assertEquals(MealSlot.DINNER, state.slot)
        assertEquals(FoodAddPreviewData.NOW, state.time)
    }

    /** Returning from the picker re-runs `start`, and what has been typed must survive it. */
    @Test
    fun `starting again on the same target keeps what was typed`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("120")

        add.viewModel.start(TODAY, MealSlot.LUNCH, null)

        assertEquals("120", state(add).amount?.quantity)
    }

    /** A `+` pressed on another moment is another line, and starts from nothing. */
    @Test
    fun `starting on another target begins afresh`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("120")

        add.viewModel.start(TODAY, MealSlot.DINNER, null)

        val state = state(add)
        assertEquals(FoodAddStage.PATHS, state.stage)
        assertEquals(MealSlot.DINNER, state.slot)
    }

    // endregion

    // region the moment and the hour follow each other (PRD_FOOD 10.3)

    /** FR-FOOD-007: a moment nobody has chosen follows the hour that is typed. */
    @Test
    fun `choosing an hour preselects the moment, until a moment is chosen by hand`() =
        addTest { add ->
            add.viewModel.start(null, null, null)

            add.viewModel.onTimePicked(LocalTime.of(10, 0))
            // PRD_FOOD 22: "une pomme à dix heures est proposée en collation".
            assertEquals(MealSlot.SNACK, state(add).slot)

            add.viewModel.onSlotSelected(MealSlot.LUNCH)
            add.viewModel.onTimePicked(LocalTime.of(21, 0))
            assertEquals(MealSlot.LUNCH, state(add).slot)
        }

    /** The other direction: an hour nobody has typed follows the moment. */
    @Test
    fun `changing the moment moves an untouched hour on a past day`() = addTest { add ->
        add.viewModel.start(YESTERDAY, MealSlot.BREAKFAST, null)
        assertEquals(MealSlot.BREAKFAST.defaultTime, state(add).time)

        add.viewModel.onSlotSelected(MealSlot.DINNER)
        assertEquals(MealSlot.DINNER.defaultTime, state(add).time)

        add.viewModel.onTimePicked(LocalTime.of(21, 15))
        add.viewModel.onSlotSelected(MealSlot.SNACK)
        assertEquals(LocalTime.of(21, 15), state(add).time)
    }

    // endregion

    // region the quantity (FR-FOOD-006)

    @Test
    fun `the counter fills the weight field with what it resolves to`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.SNACK, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.apple().id)
        advanceUntilIdle()

        add.viewModel.onPortionStep(up = true)
        add.viewModel.onPortionStep(up = true)

        val amount = assertNotNull(state(add).amount)
        assertEquals("1", amount.portionsValue)
        // One apple is 150 g, and the field says so rather than leaving the number implicit.
        assertEquals("150", amount.quantity)
    }

    /** PRD_FOOD 8.6: "la saisie exacte en grammes reprend toujours la main sur la portion". */
    @Test
    fun `typing a weight drops the portion counter`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.SNACK, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.apple().id)
        advanceUntilIdle()
        add.viewModel.onPortionStep(up = true)

        add.viewModel.onQuantityChange("180")

        val amount = assertNotNull(state(add).amount)
        assertNull(amount.portions)
        assertEquals("180", amount.quantity)
    }

    /** A number field takes numbers; a stray letter never reaches the validator. */
    @Test
    fun `the quantity field keeps digits and both separators`() {
        assertEquals("62,5", FoodAddViewModel.number("62,5 g"))
        assertEquals("62.5", FoodAddViewModel.number("abc62.5"))
        assertEquals("12345678", FoodAddViewModel.number("1234567890"))
    }

    // endregion

    // region writing the line (PRD_FOOD 8.4)

    @Test
    fun `saving writes one line, with its values frozen on it`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("80")

        add.viewModel.save()
        advanceUntilIdle()

        val saved = add.logs.saved.single()
        assertEquals(FoodLogKind.FOOD, saved.kind)
        assertEquals(MealSlot.LUNCH, saved.slot)
        assertEquals(TODAY, saved.consumedOn)
        assertEquals(FoodAddPreviewData.RICE_NAME, saved.title)
        assertEquals(Quantity.ofIngredientOrNull(80.0), saved.measuredQuantity)
        assertEquals(279_200, saved.nutrients.energy?.milliKcal)
        assertTrue(state(add).justSaved)
    }

    /** PRD_FOOD 15 and 17: a refusal lands beside its field and nothing is written or lost. */
    @Test
    fun `a refused quantity writes nothing and keeps the form`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("0")

        add.viewModel.save()
        advanceUntilIdle()

        val state = state(add)
        assertTrue(add.logs.saved.isEmpty())
        assertEquals(FoodValidation.INGREDIENT_QUANTITY_ERROR, state.errors.quantity)
        assertEquals("0", state.amount?.quantity)
        assertFalse(state.justSaved)
    }

    /** Any edit takes the refusal back, so a message never outlives what caused it. */
    @Test
    fun `typing again clears the refusal`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.save()
        advanceUntilIdle()
        assertNotNull(state(add).errors.quantity)

        add.viewModel.onQuantityChange("80")

        assertNull(state(add).errors.quantity)
    }

    @Test
    fun `a quick add is written with no quantity at all`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.SNACK, null)
        add.viewModel.onQuickAddChosen()
        add.viewModel.onQuickTitleChange(FoodAddPreviewData.QUICK_NAME)
        add.viewModel.onQuickEnergyChange("420")

        add.viewModel.save()
        advanceUntilIdle()

        val saved = add.logs.saved.single()
        assertEquals(FoodLogKind.QUICK, saved.kind)
        assertNull(saved.quantityUnit)
        assertEquals(420_000, saved.nutrients.energy?.milliKcal)
        // PRD_FOOD 13.1: nobody said there was no protein, so nobody may write a zero.
        assertNull(saved.nutrients.protein)
    }

    // endregion

    // region correcting a line (FR-FOOD-008)

    @Test
    fun `a stored line reopens on its own values and saves back onto itself`() = addTest(
        entries = listOf(storedRice()),
    ) { add ->
        add.viewModel.start(null, null, storedRice().id)
        advanceUntilIdle()

        val opened = state(add)
        assertTrue(opened.isEditing)
        assertEquals(FoodAddStage.AMOUNT, opened.stage)
        assertEquals("80", opened.amount?.quantity)

        add.viewModel.onQuantityChange("120")
        add.viewModel.save()
        advanceUntilIdle()

        val saved = add.logs.saved.single()
        assertEquals(storedRice().id, saved.id)
        assertEquals(Quantity.ofIngredientOrNull(120.0), saved.measuredQuantity)
    }

    @Test
    fun `deleting removes the line it was opened on`() = addTest(entries = listOf(storedRice())) {
        add ->
        add.viewModel.start(null, null, storedRice().id)
        advanceUntilIdle()

        add.viewModel.delete()
        advanceUntilIdle()

        assertEquals(listOf(storedRice().id), add.logs.deleted)
        assertTrue(state(add).justDeleted)
    }

    /** A line deleted from another screen leaves the sheet with nothing to correct. */
    @Test
    fun `a line that has gone leaves the sheet on its first stage`() = addTest { add ->
        add.viewModel.start(null, null, storedRice().id)
        advanceUntilIdle()

        assertEquals(FoodAddStage.PATHS, state(add).stage)
    }

    // endregion

    // region what survives (PRD 16.4)

    @Test
    fun `a typed draft comes back after the process dies`() {
        val savedState = SavedStateHandle()

        addTest(savedState = savedState) { add ->
            add.viewModel.start(TODAY, MealSlot.LUNCH, null)
            add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
            advanceUntilIdle()
            add.viewModel.onQuantityChange("7,")
        }

        // The same handle, a new ViewModel: what a process death actually looks like.
        addTest(savedState = savedState) { add ->
            add.viewModel.start(TODAY, MealSlot.LUNCH, null)

            val state = state(add)
            assertEquals(FoodAddStage.AMOUNT, state.stage)
            assertEquals("7,", state.amount?.quantity)
        }
    }

    // endregion

    // region leaving the sheet, and getting out of a path (PRD_FOOD 7)

    /*
     * The owner's report, twice over:
     *
     *   "je suis bloqué dans un mode, j'ai fait add what you ate, add custom machin, et je suis là
     *    dans add food, et si je fais add what you ate je ne fais que tomber sur add food, j'ai
     *    plus accès aux 3 menus d'avant."
     *
     * Two defects behind one sentence. Inside the sheet, choosing a path was final: nothing
     * returned to the ways in short of saving or deleting a line. Outside it, `Close` kept the
     * draft whatever it held, so the next `+` on the same moment found the same target, resumed,
     * and landed on `How much?` again — for good.
     */

    @Test
    fun `the sheet goes back to the ways in from a chosen food`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("80")
        assertEquals(FoodAddStage.AMOUNT, state(add).stage)

        add.viewModel.onBackToPaths()

        assertEquals(FoodAddStage.PATHS, state(add).stage)
    }

    @Test
    fun `the sheet goes back to the ways in from a quick add`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.SNACK, null)
        add.viewModel.onQuickAddChosen()
        add.viewModel.onQuickTitleChange(FoodAddPreviewData.QUICK_NAME)
        assertEquals(FoodAddStage.QUICK, state(add).stage)

        add.viewModel.onBackToPaths()

        assertEquals(FoodAddStage.PATHS, state(add).stage)
    }

    /**
     * The step undoes the **path** and nothing else.
     *
     * The moment and the hour came in with the `+` that opened the sheet, or were set by hand
     * afterwards; neither was chosen on the path being left. A back step that also moved the entry
     * to another time of day would answer "wrong way in" with a second mistake.
     */
    @Test
    fun `going back keeps the day, the moment and the hour`() = addTest { add ->
        add.viewModel.start(YESTERDAY, MealSlot.BREAKFAST, null)
        add.viewModel.onTimePicked(LocalTime.of(9, 15))
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("80")

        add.viewModel.onBackToPaths()

        val state = state(add)
        assertEquals(YESTERDAY, state.date)
        assertEquals(MealSlot.BREAKFAST, state.slot)
        assertEquals(LocalTime.of(9, 15), state.time)
        // What the path did set is gone, so the next choice starts from nothing.
        assertNull(state.food)
        assertNull(state.amount)
    }

    /**
     * FR-FOOD-008: a correction was not opened on the ways in and has no earlier stage.
     *
     * Offering the step there would offer to turn a weighed food into a quick add, which is not
     * a correction of the line but the loss of it.
     */
    @Test
    fun `a correction is never offered the way back`() = addTest(entries = listOf(storedRice())) {
        add ->
        add.viewModel.start(null, null, storedRice().id)
        advanceUntilIdle()

        assertFalse(state(add).canReturnToPaths)
    }

    @Test
    fun `a new line past the first stage is offered the way back`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        assertFalse(state(add).canReturnToPaths)

        add.viewModel.onQuickAddChosen()

        assertTrue(state(add).canReturnToPaths)
    }

    /**
     * The second half: a sheet abandoned with nothing typed does not come back as it was.
     *
     * This is the trap itself. `Search a food`, a food picked, `Close` — and the `+` of the same
     * moment reopened on `How much?` with an empty field, the three ways in unreachable. A chosen
     * food is one tap, and one tap is not work worth keeping someone out of the sheet's first
     * stage for.
     */
    @Test
    fun `leaving with nothing typed reopens on the ways in`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        assertEquals(FoodAddStage.AMOUNT, state(add).stage)

        add.viewModel.onLeft()
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)

        assertEquals(FoodAddStage.PATHS, state(add).stage)
    }

    /**
     * And the half that must not break with it.
     *
     * The resume exists so a weight mid-entry survives; PRD_FOOD 15 keeps a half-typed `7,`
     * exactly as it was typed, and a `Close` pressed by accident may not be what throws it away.
     */
    @Test
    fun `leaving with something typed reopens where it was left`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("7,")

        add.viewModel.onLeft()
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)

        val state = state(add)
        assertEquals(FoodAddStage.AMOUNT, state.stage)
        assertEquals("7,", state.amount?.quantity)
    }

    /**
     * The picker round trip, which is the reason the resume was written and must keep working.
     *
     * Going to the picker is not leaving: the sheet is pushed over, not closed, so nothing calls
     * `onLeft` and `start` finds the same target and resumes. This is that path driven exactly as
     * `FoodNavHost` drives it — a chosen food, then the sheet re-entering composition and calling
     * `start` again.
     */
    @Test
    fun `returning from the picker keeps the food it chose`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()

        // No `onLeft`: the picker was pushed over the sheet and has just been popped off it.
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)

        val state = state(add)
        assertEquals(FoodAddStage.AMOUNT, state.stage)
        assertNotNull(state.food)
    }

    /** Once the line is written the sheet is done with, so the next one opens on nothing. */
    @Test
    fun `the flow is forgotten once its line has been saved`() = addTest { add ->
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)
        add.viewModel.onFoodChosen(FoodAddPreviewData.rice().id)
        advanceUntilIdle()
        add.viewModel.onQuantityChange("80")
        add.viewModel.save()
        advanceUntilIdle()

        add.viewModel.onSaveConfirmationFinished()
        add.viewModel.start(TODAY, MealSlot.LUNCH, null)

        assertEquals(FoodAddStage.PATHS, state(add).stage)
    }

    // endregion

    // region harness

    private class Add(
        val viewModel: FoodAddViewModel,
        val logs: FakeFoodLogRepository,
        val foods: RecordingFoodCatalogueRepository,
        val lookup: FakeProductLookup,
    )

    /**
     * Subscribes the state before the body runs, because `WhileSubscribed` only reads the stores
     * while something is listening — exactly as the screen does.
     */
    private fun addTest(
        entries: List<FoodLogEntry> = emptyList(),
        catalogue: List<Food> = FoodAddPreviewData.catalogue(),
        savedState: SavedStateHandle = SavedStateHandle(),
        lookup: FakeProductLookup = FakeProductLookup(),
        body: suspend TestScope.(Add) -> Unit,
    ) = runTest(mainDispatcher) {
        val logs = FakeFoodLogRepository(entries)
        val foods = RecordingFoodCatalogueRepository(catalogue)
        val add = Add(
            viewModel = FoodAddViewModel(
                logs = logs,
                foods = foods,
                lookup = lookup,
                savedState = savedState,
                clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                locale = { Locale.UK },
            ),
            logs = logs,
            foods = foods,
            lookup = lookup,
        )

        val collector = launch { add.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(add)

        collector.cancel()
    }

    /** The state once every pending emission has landed. */
    private fun TestScope.state(add: Add): FoodAddUiState {
        advanceUntilIdle()
        return add.viewModel.uiState.value
    }

    /** 80 g of rice, already in the journal, for the correction tests. */
    private fun storedRice(): FoodLogEntry {
        val rice = FoodAddPreviewData.rice()
        val draft = FoodAddPreviewData.draft(MealSlot.LUNCH).copy(
            foodId = rice.id.value,
            quantity = "80",
        )
        val resolved = draft.resolve(
            food = rice,
            original = null,
            today = TODAY,
            id = FoodLogEntryId("stored-rice"),
        )
        return (resolved as FoodAddResolution.Ready).entry
    }

    // endregion
}
