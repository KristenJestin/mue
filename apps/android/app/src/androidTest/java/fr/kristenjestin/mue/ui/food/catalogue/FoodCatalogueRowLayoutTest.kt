package fr.kristenjestin.mue.ui.food.catalogue

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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The narrowest phone PRD_FOOD 18 has to hold on, which is where the row runs out of room. */
private val NarrowestPhone = 360.dp

/** The largest text size the system offers, and the reader's choice rather than a suggestion. */
private const val LargestFontScale = 2f

/**
 * What the glyphs of a catalogue row actually do, rather than what its semantics string says.
 *
 * Every assertion in [FoodsScreenTest] is blind to this class of defect **by construction**.
 * `onNodeWithText` matches the semantics string, which stays the whole name however the text is
 * laid out — so a food name squeezed to one letter per line, or ellipsised away entirely, passes
 * the lot. PRD_FOOD 15 lets a name run to eighty characters, and the journal's own cards came
 * apart in exactly this way at scale 2.0 before the split was measured rather than weighted.
 *
 * So these tests read the [TextLayoutResult] the text node hands out — the same object the
 * renderer used — and assert about pixels:
 *
 * - **no word is broken.** `MultiParagraph.minIntrinsicWidth` is the width of the longest word in
 *   the string; a paragraph at least that wide never has to break one.
 * - **every character is drawn.** `getLineEnd(visibleEnd = true)` stops at the ellipsis, so
 *   comparing it with the string's length says whether the reader is seeing all of it.
 *
 * The width is pinned at [NarrowestPhone] rather than taken from the device, so the test says the
 * same thing on any emulator.
 */
@RunWith(AndroidJUnit4::class)
class FoodCatalogueRowLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** PRD_FOOD 15's 80-character ceiling, at PRD_FOOD 18's largest text size. */
    @Test
    fun theLongestNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setCatalogue(fontScale = LargestFontScale)

        val longest = rows().first { it.name == FoodCataloguePreviewData.LONGEST_NAME }
        assertWordsUnbroken(layoutOf(longest, longest.name))
    }

    @Test
    fun everyNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setCatalogue(fontScale = LargestFontScale)

        rows().forEach { row -> assertWordsUnbroken(layoutOf(row, row.name)) }
    }

    /**
     * An energy is a single reading. Half of `≈ 370 kcal` is not a smaller number, it is a
     * misreading — so it belongs on one line or on none.
     */
    @Test
    fun anEnergyStaysOnOneLineAtTwiceTheFontScale() {
        setCatalogue(fontScale = LargestFontScale)

        rows().forEach { row ->
            val energy = row.figures.firstOrNull() ?: return@forEach
            val layout = layoutOf(row, energy)

            assertEquals(
                "«$energy» is drawn over ${layout.lineCount} lines",
                1,
                layout.lineCount,
            )
            assertNothingDropped(layout)
        }
    }

    /** The scale the rows are already right at, and the one the measured split must not disturb. */
    @Test
    fun theOrdinaryFontScaleIsUntouched() {
        setCatalogue(fontScale = 1f)

        rows().forEach { row ->
            assertWordsUnbroken(layoutOf(row, row.name))
            row.figures.firstOrNull()?.let { assertEquals(1, layoutOf(row, it).lineCount) }
        }
    }

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

    /** Every row the browse view puts on the glass, recents and catalogue alike. */
    private fun rows(): List<FoodRowUiState> =
        previewFoodsState().let { it.recent + it.results }

    /**
     * The layout of [text] as drawn inside [row]'s card.
     *
     * The search is scoped to the card and runs on the unmerged tree: the card announces itself
     * whole through `clearAndSetSemantics`, so its own strings are only reachable there.
     */
    private fun layoutOf(row: FoodRowUiState, text: String): TextLayoutResult {
        val tag = FoodTestTags.foodCard(row.id.value)
        compose.onNodeWithTag(FoodTestTags.FOOD_LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()

        val node = compose
            .onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode("no node drawing «$text» inside $tag")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«$text» reported no layout")
    }

    private fun setCatalogue(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(NarrowestPhone).fillMaxHeight()) {
                        FoodsScreen(
                            state = previewFoodsState(),
                            onQueryChange = {},
                            onClearQuery = {},
                            onSourceChange = {},
                            onOpenFood = {},
                            onCreateFood = {},
                            onOpenPreferences = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
