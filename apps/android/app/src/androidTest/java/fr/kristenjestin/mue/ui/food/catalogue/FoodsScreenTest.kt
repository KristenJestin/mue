package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalogue as it reaches the glass (PRD_FOOD 9.4, 13.2, 16.3, 17 and 18).
 *
 * The screen is driven through its stateless composable, so every assertion is about what is
 * drawn rather than about how a ViewModel got there — the split `FoodDayScreenTest` already uses.
 * Expected strings come from [FoodLabels] and [FoodCatalogueMessages] rather than being spelled
 * out, so a rule that moves cannot leave a test agreeing with a copy of itself.
 */
@RunWith(AndroidJUnit4::class)
class FoodsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened: FoodId? = null
    private var created: Int = 0
    private var preferences: Int = 0
    private var typed: String? = null

    private val shown = mutableStateOf(previewFoodsState())

    // region a known zero, an unknown, and a figure withheld

    /**
     * PRD_FOOD 13.2 on the glass: `≈ 0.0 g fibre` and `— fibre` are two different cards.
     *
     * The search is scoped to each card, because both strings appear elsewhere on a catalogue
     * screen and a sweep of the tree could not say which row it had found.
     */
    @Test
    fun aKnownZeroAndAnUnknownAreDrawnDifferently() {
        setFoods(previewFoodsState())

        val coffee = FoodCataloguePreviewData.blackCoffee().id
        scrollTo(FoodTestTags.foodCard(coffee.value))
        assertDrawnInside(FoodTestTags.foodCard(coffee.value), "≈ 0.0 g fibre")

        val yoghurt = FoodCataloguePreviewData.greekYoghurt().id
        scrollTo(FoodTestTags.foodCard(yoghurt.value))
        assertDrawnInside(FoodTestTags.foodCard(yoghurt.value), "${FoodLabels.UNKNOWN} fibre")
    }

    /** PRD_FOOD 15: a card with no value at all draws dashes and never a row of zeros. */
    @Test
    fun aFoodWithNoValueDrawsDashes() {
        setFoods(previewFoodsState())

        val cake = FoodCataloguePreviewData.auntsCake().id
        scrollTo(FoodTestTags.foodCard(cake.value))

        assertDrawnInside(FoodTestTags.foodCard(cake.value), FoodLabels.UNKNOWN)
        assertDrawnInside(FoodTestTags.foodCard(cake.value), "— protein   — carbs   — fat   — fibre")
    }

    /**
     * PRD_FOOD 22: "masquer l'énergie depuis les préférences retire tous les chiffres
     * nutritionnels sans casser un parcours".
     *
     * The same screen is shown twice so that what is compared is what was drawn. With the
     * preference off no figure is anywhere on the list, and every card is still there, still
     * named, still openable.
     */
    @Test
    fun hidingEnergyRemovesEveryFigureAndBreaksNothing() {
        setFoods(previewFoodsState())

        val yoghurt = FoodTestTags.foodCard(FoodCataloguePreviewData.greekYoghurt().id.value)
        scrollTo(yoghurt)
        assertDrawnInside(yoghurt, "≈ 59 kcal")

        showFoods(hiddenEnergyFoodsState())
        scrollTo(yoghurt)

        compose.onNodeWithTag(yoghurt).assertIsDisplayed()
        compose.onNodeWithText("≈ 59 kcal", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(FoodLabels.ENERGY_UNIT, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()

        // The row still names the food, so the catalogue is still a catalogue.
        compose.onNodeWithText(FoodCataloguePreviewData.YOGHURT_NAME, useUnmergedTree = true)
            .assertExists()

        // And it is still what opens the card.
        compose.onNodeWithTag(yoghurt).performClick()
        assertEquals(FoodCataloguePreviewData.greekYoghurt().id, opened)
    }

    // endregion

    // region searching (PRD_FOOD 9.4 and 17)

    @Test
    fun typingReachesTheViewModel() {
        setFoods(previewFoodsState())

        /*
         * Aimed at the editable node, not at the handle. `FoodTestTags.FOOD_SEARCH` sits on
         * `MueSearchField`'s outer row — the pill with its icon, its border and its clear button —
         * and that row carries no text action of its own, so `performTextReplacement` on it could
         * only ever fail with "RequestFocus is defined". The picker's own search test had the
         * same mistake.
         */
        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.FOOD_SEARCH)),
        ).performTextReplacement("oats")

        assertEquals("oats", typed)
    }

    /** PRD_FOOD 17: a search with no result names the term and offers to create it. */
    @Test
    fun aFruitlessSearchOffersTheCreationPrefilled() {
        setFoods(noMatchFoodsState("kombucha"))

        compose.onNodeWithText(FoodCatalogueMessages.noMatch("kombucha")).assertExists()
        compose.onNodeWithTag(FoodTestTags.CREATE_FOOD).performClick()

        assertEquals(1, created)
    }

    /** PRD_FOOD 9.4 and 9.5: a page of a thousand-entry catalogue admits that it is a page. */
    @Test
    fun aFullPageSaysSo() {
        val rows = List(FoodCatalogueViewModel.RESULT_LIMIT) {
            FoodRowUiState.of(FoodCataloguePreviewData.rolledOats().copy(id = FoodId("row-$it")))
        }
        setFoods(FoodsUiState(isLoading = false, results = rows))

        compose.onNodeWithTag(FoodTestTags.FOOD_LIST).performScrollToNode(
            hasText(FoodCatalogueMessages.showingFirst(FoodCatalogueViewModel.RESULT_LIMIT)),
        )
        compose.onNodeWithText(
            FoodCatalogueMessages.showingFirst(FoodCatalogueViewModel.RESULT_LIMIT),
        ).assertExists()
    }

    // endregion

    // region the way in and out

    @Test
    fun tappingAFoodOpensThatFood() {
        setFoods(previewFoodsState())

        val oats = FoodTestTags.foodCard(FoodCataloguePreviewData.rolledOats().id.value)
        scrollTo(oats)
        compose.onNodeWithTag(oats).performClick()

        assertEquals(FoodCataloguePreviewData.rolledOats().id, opened)
    }

    /** PRD_FOOD 6.7: the settings live in the preferences, and this is the door to them. */
    @Test
    fun thePreferencesAreReachableAndNamed() {
        setFoods(previewFoodsState())

        compose.onNodeWithTag(FoodTestTags.OPEN_PREFERENCES)
            .assertContentDescriptionContains(FoodCatalogueMessages.OPEN_PREFERENCES)
        compose.onNodeWithTag(FoodTestTags.OPEN_PREFERENCES).performClick()

        assertEquals(1, preferences)
    }

    // endregion

    // region accessibility (PRD_FOOD 18)

    /** A card is one announcement carrying its values with their units and their `about`. */
    @Test
    fun aCardAnnouncesItselfWhole() {
        setFoods(previewFoodsState())

        val yoghurt = FoodTestTags.foodCard(FoodCataloguePreviewData.greekYoghurt().id.value)
        scrollTo(yoghurt)

        compose.onNodeWithTag(yoghurt)
            .assertContentDescriptionContains(FoodCataloguePreviewData.YOGHURT_NAME, substring = true)
        compose.onNodeWithTag(yoghurt)
            .assertContentDescriptionContains("about 59 kcal", substring = true)
        compose.onNodeWithTag(yoghurt)
            .assertContentDescriptionContains("unknown fibre", substring = true)
    }

    @Test
    fun everyControlClearsTheTouchMinimum() {
        setFoods(previewFoodsState())

        assertTallEnough(FoodTestTags.OPEN_PREFERENCES)
        assertTallEnough(FoodTestTags.FOOD_SEARCH)
        assertTallEnough(FoodTestTags.CREATE_FOOD)

        val oats = FoodTestTags.foodCard(FoodCataloguePreviewData.rolledOats().id.value)
        scrollTo(oats)
        assertTallEnough(oats)
    }

    // endregion

    /**
     * The last card of the list is reachable, band and all.
     *
     * `Log activity` once shipped a 112 dp strip at the foot of its scroll that no thumb could
     * touch: the whole pinned band had been subtracted from the viewport, ramp included, so
     * content came to rest under chrome that swallows gestures. The list's own padding here is
     * the **solid block alone**; this asserts the consequence — the bottom of the last card sits
     * above the top of the solid block.
     */
    @Test
    fun theLastCardIsNotHidingUnderThePinnedAction() {
        setFoods(previewFoodsState())

        val last = FoodTestTags.foodCard(FoodCataloguePreviewData.auntsCake().id.value)
        scrollTo(last)

        val cardBottom = compose.onNodeWithTag(last).getUnclippedBoundsInRoot().bottom
        val actionTop = compose.onNodeWithTag(FoodTestTags.CREATE_FOOD)
            .getUnclippedBoundsInRoot()
            .top

        assertTrue(
            "the last card ends at $cardBottom, under an action that starts at $actionTop",
            cardBottom <= actionTop,
        )
    }

    // region harness

    /**
     * Asserts that the card handled by [tag] draws [text] somewhere inside itself.
     *
     * `substring = true`, because a catalogue row draws its four macronutrients as **one** string
     * — `≈ 0.0 g protein   ≈ 0.0 g carbs   ≈ 0.0 g fat   ≈ 0.0 g fibre` — so that they wrap
     * together rather than being squeezed onto one line. `hasText(x)` matches an *element* of a
     * node's text list, not a part of one, so asking for `≈ 0.0 g fibre` on its own could never
     * match and never did: the assertion was looking for a node the row stopped drawing when its
     * figures were joined. It is still scoped to the card, which is what keeps `≈ 0.0 g fibre`
     * and `— fibre` provably on two different rows.
     */
    private fun assertDrawnInside(tag: String, text: String) {
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun assertTallEnough(tag: String) {
        val height = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
        assertTrue("$tag is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FoodTestTags.FOOD_LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /**
     * `setContent` may only be called once per test, and one test below has to compare two
     * states on the same glass — so the state is swapped rather than the content set again.
     */
    private fun setFoods(state: FoodsUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                FoodsScreen(
                    state = shown.value,
                    onQueryChange = { typed = it },
                    onClearQuery = { typed = "" },
                    onSourceChange = {},
                    onOpenFood = { opened = it },
                    onCreateFood = { created++ },
                    onOpenPreferences = { preferences++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showFoods(state: FoodsUiState) {
        shown.value = state
        compose.waitForIdle()
    }

    // endregion
}
