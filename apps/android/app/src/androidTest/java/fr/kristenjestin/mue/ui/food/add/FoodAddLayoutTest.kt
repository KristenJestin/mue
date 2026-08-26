package fr.kristenjestin.mue.ui.food.add

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
 * What the glyphs of the add flow actually do, rather than what its semantics strings say.
 *
 * Every assertion in [FoodAddScreenTest] and [FoodPickerScreenTest] is blind to this class of
 * defect **by construction**: `onNodeWithText` matches the semantics string, which stays the whole
 * name however the text is laid out — so a title squeezed to one letter per line, or ellipsised
 * away entirely, passes the lot. `FoodDayEntryCardLayoutTest` is where that was learned; this is
 * the same reading applied to the two screens the add flow adds.
 *
 * Three things are load-bearing here and none of them may be cut:
 *
 * - a **food name**, which PRD_FOOD 15 lets run to 80 characters and the catalogue fills;
 * - a **figure**, because half of `≈ 1204 kcal` is a wrong number and half of `—` is nothing;
 * - the **state word beside the quantity field**, because `Weight, cooked` cut to `Weight` is the
 *   very misreading the label exists to prevent.
 */
class FoodAddLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    // region the sheet

    /** PRD_FOOD 15's 80-character ceiling, on the card that names the chosen food. */
    @Test
    fun theLongestFoodNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        showSheet(previewLongNameState(), LargestFontScale)

        assertWordsUnbroken(layoutOf(FoodTestTags.ADD_SHEET, FoodAddPreviewData.LONGEST_NAME))
    }

    /**
     * The state word beside the quantity field, at the size that would drop it.
     *
     * `Weight, cooked` is drawn by a `maxLines = 1` label, so an ellipsis here is silent and
     * would leave the field saying nothing about which reading the number is.
     */
    @Test
    fun theQuantityFieldsStateWordSurvivesTwiceTheFontScale() {
        showSheet(previewCookedState(), LargestFontScale)

        assertNothingDropped(layoutOf(FoodTestTags.QUANTITY_FIELD, "Weight, cooked"))
    }

    /** PRD_FOOD 13.1's conversion, said in full or not at all. */
    @Test
    fun theReferenceWeightIsDrawnWholeAtTwiceTheFontScale() {
        showSheet(previewCookedState(), LargestFontScale)

        val note = FoodAddMessages.countedAs("265.487 g", "raw")
        assertWordsUnbroken(layoutOf(FoodTestTags.ADD_SHEET, note))
    }

    /**
     * Every nutrient row, both halves.
     *
     * This is where a plain `Row` came apart on the `Day` screen: the figure took the width it
     * wanted and the label was left a ribbon. the shipped `MueSplitRow` stacks them instead, so both
     * are still laid out at a width that fits their longest word.
     */
    @Test
    fun everyFigureKeepsItsLabelAndItsValueWholeAtTwiceTheFontScale() {
        showSheet(previewCookedState(), LargestFontScale)

        previewCookedState().figures!!.rows.forEach { row ->
            val tag = FoodTestTags.nutrientField(row.key)
            assertWordsUnbroken(layoutOf(tag, row.label))
            assertOneLine(layoutOf(tag, row.value))
        }
    }

    /** The scale the sheet has to be right at, and the one the measured split leaves alone. */
    @Test
    fun theOrdinaryFontScaleIsUntouched() {
        showSheet(previewLongNameState(), fontScale = 1f)

        assertWordsUnbroken(layoutOf(FoodTestTags.ADD_SHEET, FoodAddPreviewData.LONGEST_NAME))
        previewLongNameState().figures!!.rows.forEach { row ->
            assertOneLine(layoutOf(FoodTestTags.nutrientField(row.key), row.value))
        }
    }

    // endregion

    // region the picker

    /** A catalogue name is never cut in a list of catalogue names. */
    @Test
    fun aPickerRowKeepsItsNameWholeAtTwiceTheFontScale() {
        showPicker(LargestFontScale)

        val tag = FoodTestTags.foodCard(FoodAddPreviewData.longNamed().id.value)
        compose.onNodeWithTag(FoodTestTags.SEARCH_RESULTS).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()

        assertWordsUnbroken(layoutOf(tag, FoodAddPreviewData.LONGEST_NAME))
    }

    /** A `—` in a list is a fact about a food; half of one is nothing at all. */
    @Test
    fun aPickerRowKeepsItsEnergyWholeAtTwiceTheFontScale() {
        showPicker(LargestFontScale)

        val tag = FoodTestTags.foodCard(FoodAddPreviewData.rice().id.value)
        compose.onNodeWithTag(FoodTestTags.SEARCH_RESULTS).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()

        assertOneLine(layoutOf(tag, "≈ 349 kcal"))
        assertNothingDropped(layoutOf(tag, FoodAddMessages.SOURCE_CIQUAL))
    }

    // endregion

    // region harness

    /**
     * Asserts that [layout] never had to break a word to fit the width it was given.
     *
     * `minIntrinsicWidth` of a paragraph is the width of its longest word; a paragraph at least
     * that wide never breaks one. The half-pixel is for the rounding between the float the
     * paragraph reports and the integer the layout was measured at.
     */
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

    /** A figure is a single reading: it belongs on one line or on none. */
    private fun assertOneLine(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        assertEquals("«$text» is drawn over ${layout.lineCount} lines", 1, layout.lineCount)
        assertNothingDropped(layout)
    }

    /** Asserts that the reader is shown the whole string rather than an ellipsis of it. */
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
     * Scoped and unmerged: a card announces itself whole, so its own strings are only reachable
     * there — and the same figure can legitimately be drawn twice on one screen.
     */
    private fun layoutOf(tag: String, text: String): TextLayoutResult {
        val node = compose
            .onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode("no node drawing «$text» inside $tag")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«$text» reported no layout")
    }

    private fun showSheet(state: FoodAddUiState, fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(NarrowestPhone).fillMaxHeight()) {
                        FoodAddScreen(state = state, actions = FoodAddActions())
                    }
                }
            }
        }
        // A `verticalScroll` column composes every child, on screen or not, so each of them
        // reports a text layout without the test having to walk the sheet first.
        compose.waitForIdle()
    }

    private fun showPicker(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(NarrowestPhone).fillMaxHeight()) {
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
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
