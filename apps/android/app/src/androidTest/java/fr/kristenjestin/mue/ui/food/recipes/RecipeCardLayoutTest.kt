package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The narrowest phone PRD_FOOD 18 has to hold on, which is where a row runs out of room. */
private val NarrowestPhone = 360.dp

/** The largest text size the system offers, and the reader's choice rather than a suggestion. */
private const val LargestFontScale = 2f

/**
 * What the glyphs of a recipe row actually do, rather than what its semantics string says.
 *
 * Every assertion in [RecipeListScreenTest] and [RecipeDetailScreenTest] is blind to this class
 * of defect **by construction**: `onNodeWithText` matches the semantics string, which stays the
 * whole name however the text is laid out — so a title squeezed to one letter per line, or
 * ellipsised away entirely, passes the lot. That is exactly what the journal card did at scale
 * 2.0 before the measured split landed, and the recipe rows are the same shape: a name on the
 * left, a figure on the right.
 *
 * So these tests read the [TextLayoutResult] the text node hands out — the same object the
 * renderer used — and assert about pixels:
 *
 * - **no word is broken.** `MultiParagraph.minIntrinsicWidth` is the width of the longest word in
 *   the string; a paragraph at least that wide never has to break one.
 * - **every character is drawn.** `getLineEnd(visibleEnd = true)` stops at the ellipsis, so
 *   comparing it with the string's length says whether the reader sees all of it.
 * - **a quantity keeps its own line.** Half of `260 g` is not a smaller weight, it is a
 *   misreading.
 *
 * The width is pinned at [NarrowestPhone] rather than taken from the device, so the test says the
 * same thing on any emulator.
 */
class RecipeCardLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    // region the recipe card of the list

    /** PRD_FOOD 15's 80-character ceiling, at PRD_FOOD 18's largest text size. */
    @Test
    fun aRecipeNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setList(fontScale = LargestFontScale)

        previewRecipeListState().recipes.forEach { card ->
            val tag = FoodTestTags.recipeCard(card.id.value)
            compose.onNodeWithTag(FoodTestTags.RECIPE_LIST).performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            assertWordsUnbroken(layoutOf(tag, card.name))
        }
    }

    /** A fact is either drawn or it is not; `Serves 4` down the side of a card is neither. */
    @Test
    fun aCardsFactsStayOnTheirOwnLinesAtTwiceTheFontScale() {
        setList(fontScale = LargestFontScale)

        previewRecipeListState().recipes.forEach { card ->
            val tag = FoodTestTags.recipeCard(card.id.value)
            compose.onNodeWithTag(FoodTestTags.RECIPE_LIST).performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            card.facts.forEach { fact ->
                val layout = layoutOf(tag, fact)
                assertEquals(
                    "«$fact» is drawn over ${layout.lineCount} lines",
                    1,
                    layout.lineCount,
                )
                assertNothingDropped(layout)
            }
        }
    }

    /** The scale the list is already right at, and the one no fix may disturb. */
    @Test
    fun theOrdinaryFontScaleIsUntouchedOnTheList() {
        setList(fontScale = 1f)

        previewRecipeListState().recipes.forEach { card ->
            val tag = FoodTestTags.recipeCard(card.id.value)
            compose.onNodeWithTag(FoodTestTags.RECIPE_LIST).performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            assertWordsUnbroken(layoutOf(tag, card.name))
            card.facts.forEach { fact -> assertEquals(1, layoutOf(tag, fact).lineCount) }
        }
    }

    // endregion

    // region the ingredient rows of the card

    /**
     * The row the measured split exists for: an ingredient name on the left and `≈ 541 kcal` on
     * the right, at a text size that leaves them no room for one another.
     */
    @Test
    fun anIngredientNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setCard(fontScale = LargestFontScale)

        previewRecipeDetailState().ingredients.forEach { ingredient ->
            val tag = RecipeTestTags.detailIngredient(ingredient.id)
            compose.onNodeWithTag(tag).performScrollTo()
            compose.waitForIdle()
            assertWordsUnbroken(layoutOf(tag, ingredient.name))
        }
    }

    /** A quantity is a single reading. It belongs on one line or on none — never on eleven. */
    @Test
    fun anIngredientQuantityStaysOnOneLineAtTwiceTheFontScale() {
        setCard(fontScale = LargestFontScale)

        previewRecipeDetailState().ingredients.forEach { ingredient ->
            val tag = RecipeTestTags.detailIngredient(ingredient.id)
            compose.onNodeWithTag(tag).performScrollTo()
            compose.waitForIdle()

            val layout = layoutOf(tag, ingredient.quantityLabel)
            assertEquals(
                "«${ingredient.quantityLabel}» is drawn over ${layout.lineCount} lines",
                1,
                layout.lineCount,
            )
            assertNothingDropped(layout)
        }
    }

    /** A nutrient's value is a figure, and half of one is a wrong number rather than a small one. */
    @Test
    fun aNutrientValueStaysWholeAtTwiceTheFontScale() {
        setCard(fontScale = LargestFontScale)

        compose.onNodeWithTag(FoodTestTags.RECIPE_PER_SERVING).performScrollTo()
        compose.waitForIdle()

        val block = requireNotNull(previewRecipeDetailState().perServing)
        assertNothingDropped(layoutOf(FoodTestTags.RECIPE_PER_SERVING, block.energyLabel))
        block.macros.forEach { macro ->
            val layout = layoutOf(FoodTestTags.RECIPE_PER_SERVING, macro.value)
            assertEquals(
                "«${macro.value}» is drawn over ${layout.lineCount} lines",
                1,
                layout.lineCount,
            )
            assertNothingDropped(layout)
        }
    }

    // endregion

    // region harness

    private fun assertWordsUnbroken(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        val longestWord = layout.multiParagraph.minIntrinsicWidth
        val drawnAt = layout.size.width

        assertTrue(
            "«$text» is drawn $drawnAt px wide but its longest word needs $longestWord px, " +
                "so it breaks mid-word over ${layout.lineCount} lines",
            longestWord <= drawnAt + 0.5f,
        )
        assertNothingDropped(layout)
    }

    private fun assertNothingDropped(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        val drawn = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)

        assertEquals(
            "«$text» is cut short after $drawn of its ${text.length} characters",
            text.length,
            drawn,
        )
    }

    /**
     * The layout of [text] as drawn inside the node handled by [tag].
     *
     * Scoped to the card and read on the unmerged tree: a card announces itself whole through
     * `clearAndSetSemantics`, so its own strings are only reachable there.
     */
    private fun layoutOf(tag: String, text: String): TextLayoutResult {
        val node = compose
            .onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode("no node drawing «$text» inside $tag")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«$text» reported no layout")
    }

    private fun setList(fontScale: Float) {
        compose.setContent {
            Narrow(fontScale) {
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
        }
        compose.waitForIdle()
    }

    private fun setCard(fontScale: Float) {
        compose.setContent {
            Narrow(fontScale) {
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
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Narrow(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MueTheme {
                Box(Modifier.width(NarrowestPhone).fillMaxHeight()) { content() }
            }
        }
    }

    // endregion
}
