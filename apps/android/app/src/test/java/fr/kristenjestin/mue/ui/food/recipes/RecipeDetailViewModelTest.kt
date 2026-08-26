package fr.kristenjestin.mue.ui.food.recipes

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.ui.entry.FakeUserPreferencesRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val TUESDAY_LUNCH = MealPlanKey(LocalDate.of(2026, 9, 1), MealSlot.LUNCH)
private val THURSDAY_DINNER = MealPlanKey(LocalDate.of(2026, 9, 3), MealSlot.DINNER)

/**
 * The recipe card's reads and its three writes (PRD_FOOD 11, FR-RECIPE-004 to 006).
 *
 * The two stores are faked, so what a deletion frees, what the servings counter does and how an
 * orphan ingredient reaches the state are all settled on the JVM — no emulator, no database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region reading a recipe

    @Test
    fun `the card opens on the recipe it was given`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        val state = state(card)
        assertEquals(RecipePreviewData.LONGEST_NAME, state.name)
        assertFalse(state.isMissing)
        assertEquals(3, state.ingredients.size)
    }

    @Test
    fun `a recipe that is not there says so`() = cardTest { card ->
        card.viewModel.start(RecipeId("no-such-recipe"))

        assertTrue(state(card).isMissing)
    }

    /**
     * PRD_FOOD 21.2, end to end: the catalogue simply does not hold the lentils, and the card is
     * built anyway. The row keeps its snapshot, its figure is a dash, and so is every figure of
     * the recipe (PRD_FOOD 13.1).
     */
    @Test
    fun `an ingredient this device never received still reaches the card`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.CURRY_ID)

        val state = state(card)
        val orphan = state.ingredients.first { it.isOrphan }
        assertEquals(RecipePreviewData.ORPHAN_SNAPSHOT, orphan.name)
        assertEquals(FoodLabels.UNKNOWN, orphan.energyLabel)
        assertEquals(FoodLabels.UNKNOWN, assertNotNull(state.perServing).energyLabel)
        assertEquals(FoodLabels.UNKNOWN, assertNotNull(state.forServings).energyLabel)
    }

    // endregion

    // region servings (FR-RECIPE-004)

    @Test
    fun `the counter walks the servings and the quantities follow`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)
        val opened = state(card).ingredients.first().quantityLabel

        card.viewModel.onMoreServings()

        val grown = state(card)
        assertEquals(3.0, grown.servings.count)
        assertTrue(
            "the ingredient quantity did not follow the servings",
            grown.ingredients.first().quantityLabel != opened,
        )

        card.viewModel.onFewerServings()
        assertEquals(2.0, state(card).servings.count)
        assertEquals(opened, state(card).ingredients.first().quantityLabel)
    }

    /** PRD_FOOD 15's floor, enforced by the validator rather than by the screen. */
    @Test
    fun `the counter refuses to go below one serving`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)
        assertEquals(2.0, state(card).servings.count)

        card.viewModel.onFewerServings()
        assertEquals(1.0, state(card).servings.count)
        assertFalse(state(card).canRemoveServing)

        card.viewModel.onFewerServings()
        assertEquals(1.0, state(card).servings.count)
    }

    /**
     * A step taken before the card has been read walks from nothing, so it is refused.
     *
     * The default on a state that belongs to no recipe is one serving, and stepping from it would
     * silently replace the number the recipe is written for. A thumb cannot do this; an assistive
     * service can.
     */
    @Test
    fun `stepping before the card has been read changes nothing`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)
        card.viewModel.onMoreServings()

        assertEquals(2.0, state(card).servings.count)
    }

    // endregion

    // region hiding the figures (FR-FOOD-010)

    /**
     * FR-FOOD-010: "la préférence masque toutes les valeurs énergétiques et de macronutriments.
     * Le reste du module continue de fonctionner à l'identique."
     *
     * So the blocks go and the ingredient figures go — and the ingredients themselves, the steps
     * and the servings counter stay exactly where they were.
     */
    @Test
    fun `hiding the energy takes every figure and nothing else`() = cardTest(
        preferences = UserPreferences(showEnergy = false),
    ) { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        val state = state(card)
        assertNull(state.perServing)
        assertNull(state.forServings)
        assertTrue(
            "a figure survived the preference",
            state.ingredients.all { it.energyLabel == null },
        )

        assertEquals(3, state.ingredients.size)
        assertEquals(RecipePreviewData.salmon().recipe.steps, state.steps)
        assertTrue(state.canAddServing)
    }

    // endregion

    // region favourites (FR-RECIPE-005)

    @Test
    fun `the star flips through the repository`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.CURRY_ID)

        card.viewModel.onToggleFavourite()
        advanceUntilIdle()

        assertEquals(listOf(RecipePreviewData.CURRY_ID to true), card.recipes.favourited)
        assertTrue(state(card).isFavourite)
    }

    // endregion

    // region deletion (FR-RECIPE-006 and PRD_FOOD 17)

    @Test
    fun `deleting asks first and writes nothing until it is confirmed`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        card.viewModel.onRequestDelete()
        assertTrue(state(card).isConfirmingDelete)
        assertEquals(emptyList<RecipeId>(), card.recipes.deleted)

        card.viewModel.onCancelDelete()
        assertFalse(state(card).isConfirmingDelete)
        assertEquals(emptyList<RecipeId>(), card.recipes.deleted)
    }

    /**
     * **What the freed keys are for.**
     *
     * `RecipeRepository.delete` answers with the `(date, moment)` pairs whose proposal has just
     * stopped pointing at anything. PRD_FOOD 17 requires the freed moment to be *signalled*, so
     * the card holds them, names them, and only then leaves — which is why the return value is
     * not decoration.
     */
    @Test
    fun `deleting names the moments it freed`() = cardTest(
        plans = mapOf(RecipePreviewData.SALMON_ID to listOf(TUESDAY_LUNCH, THURSDAY_DINNER)),
    ) { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        card.viewModel.onRequestDelete()
        card.viewModel.onConfirmDelete()
        advanceUntilIdle()

        val state = state(card)
        assertTrue(state.isDeleted)
        assertEquals(listOf(RecipePreviewData.SALMON_ID), card.recipes.deleted)
        assertEquals(2, state.freedPlans.size)
        assertTrue(state.freedPlans.first().contains(MealSlot.LUNCH.label))
        assertTrue(state.freedPlans.last().contains(MealSlot.DINNER.label))
    }

    /** A deletion that freed no proposal has nothing to report, and the card simply leaves. */
    @Test
    fun `deleting a recipe nothing proposed reports no freed moment`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        card.viewModel.onRequestDelete()
        card.viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertTrue(state(card).isDeleted)
        assertTrue(state(card).freedPlans.isEmpty())
    }

    /** A confirmation nobody asked for writes nothing: the dialog is the only way in. */
    @Test
    fun `confirming without asking deletes nothing`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)

        card.viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(emptyList<RecipeId>(), card.recipes.deleted)
    }

    // endregion

    // region what survives a return from the editor

    /** `start` is idempotent, so coming back from the form keeps the servings that were dialled. */
    @Test
    fun `returning to the same card keeps the chosen servings`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)
        assertEquals(2.0, state(card).servings.count)

        card.viewModel.onMoreServings()
        assertEquals(3.0, state(card).servings.count)

        card.viewModel.start(RecipePreviewData.SALMON_ID)

        assertEquals(3.0, state(card).servings.count)
    }

    @Test
    fun `opening another card starts it afresh`() = cardTest { card ->
        card.viewModel.start(RecipePreviewData.SALMON_ID)
        card.viewModel.onMoreServings()

        card.viewModel.start(RecipePreviewData.CURRY_ID)

        assertEquals(RecipePreviewData.CURRY_NAME, state(card).name)
        assertEquals(4.0, state(card).servings.count)
    }

    // endregion

    // region harness

    private class Card(
        val viewModel: RecipeDetailViewModel,
        val recipes: FakeRecipeRepository,
    )

    private fun cardTest(
        details: List<RecipeDetail> = RecipePreviewData.details(),
        plans: Map<RecipeId, List<MealPlanKey>> = emptyMap(),
        preferences: UserPreferences = UserPreferences.DEFAULT,
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Card) -> Unit,
    ) = runTest(mainDispatcher) {
        val repository = FakeRecipeRepository(details, plans)
        val card = Card(
            viewModel = RecipeDetailViewModel(
                recipes = repository,
                foods = FakeFoodCatalogueRepository(RecipePreviewData.catalogue()),
                preferences = FakeUserPreferencesRepository(preferences),
                savedStateHandle = savedState,
                locale = { Locale.UK },
            ),
            recipes = repository,
        )

        val collector = launch { card.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(card)

        collector.cancel()
    }

    private fun TestScope.state(card: Card): RecipeDetailUiState {
        advanceUntilIdle()
        return card.viewModel.uiState.value
    }

    // endregion
}
