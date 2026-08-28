package fr.kristenjestin.mue.ui.food

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.food.add.FoodAddActions
import fr.kristenjestin.mue.ui.food.add.FoodAddMessages
import fr.kristenjestin.mue.ui.food.add.FoodAddScreen
import fr.kristenjestin.mue.ui.food.add.FoodPickerScreen
import fr.kristenjestin.mue.ui.food.add.RecipePickerScreen
import fr.kristenjestin.mue.ui.food.add.previewPickerState
import fr.kristenjestin.mue.ui.food.add.previewRecipePickerState
import fr.kristenjestin.mue.ui.food.add.previewRecipeServingsState
import fr.kristenjestin.mue.ui.food.catalogue.FoodCatalogueMessages
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorActions
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorScreen
import fr.kristenjestin.mue.ui.food.catalogue.previewFoodEditorState
import fr.kristenjestin.mue.ui.food.recipes.RecipeDetailScreen
import fr.kristenjestin.mue.ui.food.recipes.RecipeEditorActions
import fr.kristenjestin.mue.ui.food.recipes.RecipeEditorScreen
import fr.kristenjestin.mue.ui.food.recipes.RecipeMessages
import fr.kristenjestin.mue.ui.food.recipes.previewRecipeDetailState
import fr.kristenjestin.mue.ui.food.recipes.previewRecipeEditorState
import fr.kristenjestin.mue.ui.profile.FoodPreferencesMessages
import fr.kristenjestin.mue.ui.profile.FoodPreferencesScreen
import fr.kristenjestin.mue.ui.profile.FoodPreferencesUiState
import fr.kristenjestin.mue.ui.profile.ProfileTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Nothing sits under a sub-screen's header where no scroll can reach it.
 *
 * `MueSubScreenScaffold` dissolves the top [MueContentTopFade] of its content column so that rows
 * *leaving* the screen melt under the title rather than being cut off. At rest, though, there is
 * nothing above to dissolve — the scroll offset is zero — so whatever the screen opens with is
 * what gets faded out, and no gesture recovers it because the scroll is already at its top. The
 * owner met it on the food editor: *"le header, par exemple avec « new food », cache un bout,
 * genre là je peux pas scroll plus haut."*
 *
 * Six screens had it, because the fade is **on by default** on this scaffold while it is opt-in on
 * [fr.kristenjestin.mue.ui.components.MueScreenScaffold] — and the three view screens that opt in
 * all remembered to reserve it. Two of the six could not be rescued by scrolling at all: the
 * pickers put a fixed search field above their list, and `Food preferences` is one card shorter
 * than its own viewport.
 *
 * ## What is measured
 *
 * The back control is the last thing in the header row, so the content column begins just under
 * it. A screen that reserves the ramp puts its first control at least [MueContentTopFade] below
 * that edge; a screen that does not draws it straight into the fade. Reading real bounds is the
 * only way to see this — every string is present in the semantics tree either way, and
 * `assertIsDisplayed` is perfectly happy with a control drawn at zero alpha.
 */
class FoodSubScreenHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The recipe stage, because it is the one whose first card carries a handle.
     *
     * Anchoring on a *string* would not do here: `How much?` and `How many servings?` are each
     * drawn twice, once as the screen's title and once as its section's, so a text matcher finds
     * two nodes and fails on the ambiguity rather than on the geometry.
     */
    @Test
    fun theAddSheetClearsItsHeader() {
        setScreen {
            FoodAddScreen(state = previewRecipeServingsState(), actions = FoodAddActions())
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(FoodAddMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.CHOSEN_RECIPE),
        )
    }

    /** The screen he named. */
    @Test
    fun theFoodEditorClearsItsHeader() {
        setScreen {
            FoodEditorScreen(state = previewFoodEditorState(), actions = FoodEditorActions())
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(FoodCatalogueMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.FOOD_NAME_FIELD),
        )
    }

    @Test
    fun theRecipeEditorClearsItsHeader() {
        setScreen {
            RecipeEditorScreen(state = previewRecipeEditorState(), actions = RecipeEditorActions())
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(RecipeMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.RECIPE_NAME_FIELD),
        )
    }

    @Test
    fun theRecipeCardClearsItsHeader() {
        setScreen {
            RecipeDetailScreen(
                state = previewRecipeDetailState(),
                onBack = {},
                onEdit = {},
                onToggleFavourite = {},
                onFewerServings = {},
                onMoreServings = {},
                onRequestDelete = {},
                onCancelDelete = {},
                onConfirmDelete = {},
                onDeletionAcknowledged = {},
            )
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(RecipeMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.RECIPE_FACTS),
        )
    }

    /**
     * A fixed search field above a list: the case no scroll could ever have rescued, because the
     * control that was being faded is not part of anything that scrolls.
     */
    @Test
    fun theFoodPickerClearsItsHeader() {
        setScreen {
            FoodPickerScreen(
                state = previewPickerState(),
                onQueryChange = {},
                onClearQuery = {},
                onSourceSelected = {},
                onPicked = {},
                onCreateFood = {},
                onBack = {},
            )
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(FoodAddMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.SEARCH_FIELD),
        )
    }

    @Test
    fun theRecipePickerClearsItsHeader() {
        setScreen {
            RecipePickerScreen(
                state = previewRecipePickerState(),
                onQueryChange = {},
                onClearQuery = {},
                onPicked = {},
                onCreateRecipe = {},
                onBack = {},
            )
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(FoodAddMessages.BACK),
            firstContent = compose.onNodeWithTag(FoodTestTags.RECIPE_SEARCH),
        )
    }

    /**
     * One card, shorter than the viewport, so the scroll range is zero.
     *
     * This is the screen where the loss was total: not merely hard to reach, but unreachable by
     * any gesture at all.
     *
     * It is a `Profile` screen now, and the assertion stayed here anyway. This suite is the record
     * of one defect met on six screens at once, and six is what it has to keep measuring — a
     * screen that changed tabs did not stop being one of them. The imports say where it lives; the
     * ramp it has to clear is `MueSubScreenScaffold`'s, which is the same on either tab.
     */
    @Test
    fun theFoodPreferencesClearItsHeader() {
        setScreen {
            FoodPreferencesScreen(
                state = FoodPreferencesUiState(showEnergy = true),
                onShowEnergyChange = {},
                onBack = {},
            )
        }

        assertClearsHeader(
            back = compose.onNodeWithContentDescription(FoodPreferencesMessages.BACK),
            firstContent = compose.onNodeWithTag(ProfileTestTags.HIDE_ENERGY_TOGGLE),
        )
    }

    // region harness

    private fun setScreen(content: @Composable () -> Unit) {
        compose.setContent { MueTheme { content() } }
        compose.waitForIdle()
    }

    /**
     * The first thing on the screen begins clear of the ramp under the header.
     *
     * [MueContentTopFade] is asked of the component rather than written down, so a scaffold that
     * changes the length of its ramp moves this assertion with it.
     */
    private fun assertClearsHeader(
        back: SemanticsNodeInteraction,
        firstContent: SemanticsNodeInteraction,
    ) {
        val headerBottom: Dp = back.getUnclippedBoundsInRoot().bottom
        val contentTop: Dp = firstContent.getUnclippedBoundsInRoot().top
        val clearance = contentTop - headerBottom

        assertTrue(
            "the first control begins $clearance under the header, inside its " +
                "$MueContentTopFade ramp — it is drawn faded and no scroll reaches it",
            clearance >= MueContentTopFade,
        )
    }

    // endregion
}

