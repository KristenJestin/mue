package fr.kristenjestin.mue.ui.food.recipes

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pictures of the three recipe screens, at the ordinary font scale and at a doubled one.
 *
 * This suite exists because assertions cannot see. `MueText` ellipsises, and `onNodeWithText`
 * matches the semantics string rather than the glyphs — so an 80-character recipe name cut down
 * to `Sheet-pan salmon with charred…` passes every check in [RecipeListScreenTest] while reading
 * wrong on the phone. The only way to know is to look, and the only way to look later is to keep
 * the picture.
 *
 * The pair that matters most is `recipe-orphan` beside `recipe-empty`: one recipe whose figures
 * are all `—` because an ingredient has not arrived (PRD_FOOD 21.2), and one that shows no figure
 * at all because it has nothing to total (PRD_FOOD 13.1). Neither of them may ever show a `0`,
 * and the two pictures are what says so without reading a line of code.
 *
 * The files land beside the app's own data so `adb pull` can fetch them:
 * `/sdcard/Android/data/fr.kristenjestin.mue/files/screenshots/`.
 */
class RecipeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRecipeListAtBothFontScales() {
        setContent(fontScale = 1f) { RecipeList() }
        capture("recipe-list")
    }

    @Test
    fun theRecipeListAtTwiceTheFontScale() {
        setContent(fontScale = 2f) { RecipeList() }
        capture("recipe-list-scale2")
    }

    @Test
    fun theCardOfAWholeKnownRecipe() {
        setContent(fontScale = 1f) { Card(previewRecipeDetailState()) }
        capture("recipe-card-top")

        compose.onNodeWithTag(RecipeTestTags.RECIPE_STEPS).performScrollTo()
        compose.waitForIdle()
        capture("recipe-card-bottom")
    }

    /** PRD_FOOD 21.2 and 13.1: every figure a dash, and not one of them a zero. */
    @Test
    fun theCardOfARecipeWithAnOrphanIngredient() {
        setContent(fontScale = 1f) { Card(orphanRecipeDetailState()) }
        capture("recipe-orphan")

        compose.onNodeWithTag(RecipeTestTags.RECIPE_INGREDIENTS).performScrollTo()
        compose.waitForIdle()
        capture("recipe-orphan-ingredients")
    }

    /** Its pair: a recipe with nothing to total, which shows no figure whatsoever. */
    @Test
    fun theCardOfARecipeWithNothingToTotal() {
        setContent(fontScale = 1f) { Card(emptyRecipeDetailState()) }
        capture("recipe-empty")
    }

    @Test
    fun theCardAtTwiceTheFontScale() {
        setContent(fontScale = 2f) { Card(previewRecipeDetailState()) }
        capture("recipe-card-scale2")
    }

    @Test
    fun theFormAndWhatItRefuses() {
        setContent(fontScale = 1f) { Form(previewRecipeEditorState()) }
        capture("recipe-editor")

        compose.onNodeWithTag(FoodTestTags.INGREDIENT_LIST).performScrollTo()
        compose.waitForIdle()
        capture("recipe-editor-ingredients")
    }

    @Test
    fun theFormAfterARefusedSave() {
        setContent(fontScale = 1f) { Form(refusedRecipeEditorState()) }
        capture("recipe-editor-refused")
    }

    @Test
    fun theFormAtTwiceTheFontScale() {
        setContent(fontScale = 2f) { Form(previewRecipeEditorState()) }
        capture("recipe-editor-scale2")
    }

    // region harness

    @Composable
    private fun RecipeList() {
        RecipeListScreen(
            state = previewRecipeListState(),
            onQueryChange = {},
            onClearQuery = {},
            onTypeSelected = {},
            onToggleFavourites = {},
            onToggleFavourite = { _, _ -> },
            onOpenRecipe = {},
            onCreateRecipe = {},
        )
    }

    @Composable
    private fun Card(state: RecipeDetailUiState) {
        RecipeDetailScreen(
            state = state,
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

    @Composable
    private fun Form(state: RecipeEditorUiState) {
        RecipeEditorScreen(state = state, actions = RecipeEditorActions())
    }

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(screenshotDirectory(), "$name.png")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        assertTrue("$name captured nothing", bitmap.width > 0 && bitmap.height > 0)
        assertTrue("$name was not written", file.length() > 0)
    }

    private fun screenshotDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    // endregion
}
