package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.recipes.RecipePreviewData
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FR-FOOD-004's picker on the glass: what `Use a recipe` opens.
 *
 * **Which tree each string is in matters here.** A row is announced whole through
 * `announcedAs`, which clears the semantics of everything beneath it, so the name and the facts
 * are *not* separate nodes in the merged tree — they are one `contentDescription` on the card.
 * The assertions below read them accordingly: the drawn text through the unmerged tree, the
 * announcement through the card's own description. The headings and the empty sentences are
 * ordinary text and are asserted as such.
 */
class RecipePickerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var picked: RecipeId? = null
    private var created = 0
    private var typed: String? = null

    // region what the list shows (PRD_FOOD 11)

    @Test
    fun everySavedRecipeIsOffered() {
        show(previewRecipePickerState())

        compose.onNodeWithText(FoodAddMessages.RECIPE_RESULTS_SECTION).assertIsDisplayed()
        assertDrawn(rowTag(RecipePreviewData.CURRY_ID), RecipePreviewData.CURRY_NAME)
    }

    /** PRD_FOOD 8.3: a recipe carries no nutritional value, so a row states none. */
    @Test
    fun aRowShowsTheFactsARecipeCarriesAndNoEnergy() {
        show(previewRecipePickerState())

        assertDrawn(rowTag(RecipePreviewData.SALMON_ID), "Main · Serves 2 · 25 min")
        // Read from the *unmerged* tree, which is the only one a row's own text is in at all.
        compose.onAllNodes(hasText("kcal", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun tappingARowChoosesThatRecipe() {
        show(previewRecipePickerState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_LIST)
            .performScrollToNode(hasTestTag(rowTag(RecipePreviewData.CURRY_ID)))
        compose.onNodeWithTag(rowTag(RecipePreviewData.CURRY_ID)).performClick()

        assertEquals(RecipePreviewData.CURRY_ID, picked)
    }

    /** PRD_FOOD 18: a row is announced whole rather than as loose fragments. */
    @Test
    fun aRowAnnouncesItselfWhole() {
        show(previewRecipePickerState())

        compose.onNodeWithTag(rowTag(RecipePreviewData.CURRY_ID))
            .assertContentDescriptionContains(RecipePreviewData.CURRY_NAME, substring = true)
        compose.onNodeWithTag(rowTag(RecipePreviewData.CURRY_ID))
            .assertContentDescriptionContains("Serves 4", substring = true)
    }

    // endregion

    // region it is a sheet and not the `Recipes` view

    /**
     * The second defect, stated as a difference between two screens.
     *
     * `Use a recipe` used to land on the `Recipes` view, which is why the owner said it read as
     * the wrong page: a view carries the switcher and a bottom action, and it is not something
     * one comes back from. This screen has neither — it is a sheet with a back arrow, and its
     * whole job is to be left.
     */
    @Test
    fun thePickerIsNotTheRecipesViewAndCarriesNoSwitcher() {
        show(previewRecipePickerState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.RECIPES).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.VIEW_SWITCHER).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.RECIPE_PICKER_TITLE).assertIsDisplayed()
    }

    // endregion

    // region searching

    @Test
    fun typingReachesTheSearch() {
        show(previewRecipePickerState())

        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.RECIPE_SEARCH)),
        ).performTextInput("salmon")

        assertEquals("salmon", typed)
    }

    // endregion

    // region the empty states (PRD_FOOD 17)

    @Test
    fun aPersonWithNoRecipesIsInvitedToWriteOne() {
        show(previewEmptyRecipePickerState())

        compose.onNodeWithText(FoodAddMessages.NO_RECIPES).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.CREATE_RECIPE).assertIsDisplayed().performClick()

        assertEquals(1, created)
    }

    /** Recipes exist and this word matches none: another word, not another recipe. */
    @Test
    fun aSearchThatMatchesNothingOffersNoCreation() {
        show(
            previewRecipePickerState().copy(
                query = "sauerkraut",
                results = emptyList(),
                sectionTitle = FoodAddMessages.RESULTS_SECTION,
                emptyMessage = FoodAddMessages.NO_RECIPE_MATCHES,
            ),
        )

        compose.onNodeWithText(FoodAddMessages.NO_RECIPE_MATCHES).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.CREATE_RECIPE).assertDoesNotExist()
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyRowClearsTheTouchMinimum() {
        show(previewRecipePickerState())

        val height = compose.onNodeWithTag(rowTag(RecipePreviewData.CURRY_ID))
            .getUnclippedBoundsInRoot()
            .height
        assertTrue("a recipe row is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    // endregion

    // region harness

    private fun rowTag(id: RecipeId): String = FoodTestTags.recipeCard(id.value)

    private fun assertDrawn(tag: String, text: String) {
        compose.onNodeWithTag(FoodTestTags.RECIPE_LIST).performScrollToNode(hasTestTag(tag))
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private val shown = mutableStateOf<RecipePickerUiState?>(null)

    private fun show(state: RecipePickerUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                shown.value?.let { picker ->
                    RecipePickerScreen(
                        state = picker,
                        onQueryChange = { typed = it },
                        onClearQuery = { typed = "" },
                        onPicked = { picked = it },
                        onCreateRecipe = { created++ },
                        onBack = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
