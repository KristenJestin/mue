package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The `Recipes` view as it reaches the glass (PRD_FOOD 11, 17, 18 and FR-RECIPE-005).
 *
 * Driven through the stateless composable, so every assertion is about what is drawn rather than
 * about how a ViewModel got there — the split `FoodDayScreenTest` already uses. Expected strings
 * come from [RecipeMessages] rather than being spelled out, so a wording that changes cannot
 * leave a test agreeing with a copy of itself.
 */
class RecipeListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened: RecipeId? = null
    private var created = 0
    private var starred: Pair<RecipeId, Boolean>? = null
    private var chosenType: RecipeType? = null
    private var typeChosen = 0
    private var favouritesToggled = 0

    // region the list (PRD_FOOD 11)

    @Test
    fun everySavedRecipeGetsACard() {
        setList(previewRecipeListState())

        RecipePreviewData.recipes().forEach { recipe ->
            scrollTo(FoodTestTags.recipeCard(recipe.id.value))
            compose.onNodeWithTag(FoodTestTags.recipeCard(recipe.id.value)).assertIsDisplayed()
        }
    }

    /**
     * PRD_FOOD 8.3: a recipe stores no nutritional value, and the list has no ingredients to
     * recompute one from. A figure on a card could only be invented, so there is none.
     */
    @Test
    fun noCardClaimsAnEnergy() {
        setList(previewRecipeListState())

        compose.onNodeWithText("kcal", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun tappingACardOpensThatRecipe() {
        setList(previewRecipeListState())

        val id = RecipePreviewData.CURRY_ID
        scrollTo(FoodTestTags.recipeCard(id.value))
        compose.onNodeWithTag(FoodTestTags.recipeCard(id.value)).performClick()

        assertEquals(id, opened)
    }

    // endregion

    // region filters (FR-RECIPE-005)

    @Test
    fun theFourTypeFiltersAndTheFavouritesToggleAreThere() {
        setList(previewRecipeListState())

        scrollTo(RecipeTestTags.TYPE_FILTER)
        compose.onNodeWithText(RecipeMessages.TYPE_ALL).assertIsDisplayed()
        RecipeType.entries.forEach { type ->
            compose.onNodeWithText(type.label).assertIsDisplayed()
        }
        compose.onNodeWithTag(RecipeTestTags.FAVOURITES_FILTER).assertIsDisplayed()
    }

    @Test
    fun choosingATypeReportsThatType() {
        setList(previewRecipeListState())

        scrollTo(RecipeTestTags.TYPE_FILTER)
        compose.onNodeWithText(RecipeType.BREAKFAST.label).performClick()

        assertEquals(1, typeChosen)
        assertEquals(RecipeType.BREAKFAST, chosenType)
    }

    /** `All` is a filter cleared rather than a fourth type, and reports exactly that. */
    @Test
    fun theAllFilterClearsTheType() {
        setList(previewRecipeListState(type = RecipeType.MAIN))
        // Set to something, so that reading null afterwards is the filter clearing and not the
        // recorder never having been called.
        chosenType = RecipeType.SNACK

        scrollTo(RecipeTestTags.TYPE_FILTER)
        compose.onNodeWithText(RecipeMessages.TYPE_ALL).performClick()

        assertEquals(1, typeChosen)
        assertNull(chosenType)
    }

    @Test
    fun theFavouritesToggleReportsItsNextState() {
        setList(previewRecipeListState())

        scrollTo(RecipeTestTags.FAVOURITES_FILTER)
        compose.onNodeWithTag(RecipeTestTags.FAVOURITES_FILTER)
            .assertContentDescriptionEquals(RecipeMessages.SHOW_FAVOURITES_ONLY)
        compose.onNodeWithTag(RecipeTestTags.FAVOURITES_FILTER).performClick()

        assertEquals(1, favouritesToggled)

        setState(previewRecipeListState(favouritesOnly = true))
        scrollTo(RecipeTestTags.FAVOURITES_FILTER)
        compose.onNodeWithTag(RecipeTestTags.FAVOURITES_FILTER)
            .assertContentDescriptionEquals(RecipeMessages.SHOW_EVERY_RECIPE)
    }

    @Test
    fun theStarReportsWhatItWouldDoNext() {
        setList(previewRecipeListState())

        val favourite = RecipePreviewData.SALMON_ID
        scrollTo(FoodTestTags.favouriteRecipe(favourite.value))
        compose.onNodeWithTag(FoodTestTags.favouriteRecipe(favourite.value))
            .assertContentDescriptionEquals(RecipeMessages.REMOVE_FAVOURITE)
        compose.onNodeWithTag(FoodTestTags.favouriteRecipe(favourite.value)).performClick()

        assertEquals(favourite to false, starred)
    }

    // endregion

    // region the two empty lists (PRD_FOOD 17)

    /** Nobody has written a recipe: an invitation, a way in, and no fake recipe. */
    @Test
    fun anEmptyCatalogueInvitesRatherThanReports() {
        setList(emptyRecipeListState())

        compose.onNodeWithTag(RecipeTestTags.EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithText(RecipeMessages.NO_RECIPES_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.CREATE_RECIPE).assertIsDisplayed()
    }

    /** Recipes exist and this filter matches none, which is a different sentence. */
    @Test
    fun aFilterThatMatchesNothingSaysSoInstead() {
        setList(noMatchRecipeListState())

        compose.onNodeWithTag(RecipeTestTags.EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithText(RecipeMessages.NO_MATCH_TITLE).assertIsDisplayed()
        compose.onNodeWithText(RecipeMessages.NO_RECIPES_TITLE).assertDoesNotExist()
    }

    @Test
    fun creatingARecipeIsAlwaysOneTapAway() {
        setList(previewRecipeListState())

        compose.onNodeWithTag(FoodTestTags.CREATE_RECIPE).performClick()

        assertEquals(1, created)
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyControlClearsTheTouchMinimum() {
        setList(previewRecipeListState())

        scrollTo(RecipeTestTags.FAVOURITES_FILTER)
        assertTallEnough(RecipeTestTags.FAVOURITES_FILTER)

        val id = RecipePreviewData.SALMON_ID
        scrollTo(FoodTestTags.favouriteRecipe(id.value))
        assertTallEnough(FoodTestTags.favouriteRecipe(id.value))
        assertTallEnough(FoodTestTags.CREATE_RECIPE)
    }

    // endregion

    // region harness

    private fun assertTallEnough(tag: String) {
        val height = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
        assertTrue("$tag is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FoodTestTags.RECIPE_LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /**
     * The list on screen, held in state rather than closed over: `setContent` may only be called
     * once per test, and one of the tests above has to compare two states on the same glass.
     */
    private val shown = mutableStateOf<RecipeListUiState?>(null)

    private fun setState(state: RecipeListUiState) {
        shown.value = state
        compose.waitForIdle()
    }

    private fun setList(state: RecipeListUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                shown.value?.let { list ->
                    RecipeListScreen(
                        state = list,
                        onQueryChange = {},
                        onClearQuery = {},
                        onTypeSelected = {
                            typeChosen++
                            chosenType = it
                        },
                        onToggleFavourites = { favouritesToggled++ },
                        onToggleFavourite = { id, favourite -> starred = id to favourite },
                        onOpenRecipe = { opened = it },
                        onCreateRecipe = { created++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
