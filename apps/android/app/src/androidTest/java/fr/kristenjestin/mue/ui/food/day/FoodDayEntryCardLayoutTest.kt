package fr.kristenjestin.mue.ui.food.day

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

/** The narrowest phone PRD_FOOD 18 has to hold on, which is where the row runs out of room. */
private val NarrowestPhone = 360.dp

/** The largest text size the system offers, and the reader's choice rather than a suggestion. */
private const val LargestFontScale = 2f

/**
 * What the glyphs of a journal line actually do, rather than what its semantics string says.
 *
 * Every assertion in [FoodDayScreenTest] is blind to this class of defect **by construction**.
 * `onNodeWithText` matches the semantics string, which stays the whole name however the text is
 * laid out — so a title squeezed to one letter per line, or ellipsised away entirely, passes the
 * lot. At scale 2.0 that is exactly what the card did: the trailing energy column took the row
 * for itself, the weighted title was left a ribbon some 215 px wide, `Golden chicken grain bowl…`
 * broke *mid-word* over seventeen lines, and `1 × serving` came out as a vertical column of
 * letters ending in an ellipsis. Nothing in the suite went red.
 *
 * So these tests read the [TextLayoutResult] the text node hands out — the same object the
 * renderer used — and assert about pixels:
 *
 * - **no word is broken.** `MultiParagraph.minIntrinsicWidth` is the width of the longest word in
 *   the string; a paragraph at least that wide never has to break one. This is the whole of the
 *   defect, stated without a magic number and without naming a font metric.
 * - **every character is drawn.** `getLineEnd(visibleEnd = true)` stops at the ellipsis, so
 *   comparing it with the string's length says whether the reader is seeing all of it. The
 *   `hasVisualOverflow` flag is *not* used: it reports the paragraph's constraint against the
 *   node's measured size and goes true for text that is merely narrower than its slot.
 * - **a fact keeps its own line.** Half of `225 g` is not a smaller weight, it is a misreading.
 *
 * The width is pinned at [NarrowestPhone] rather than taken from the device, so the test says the
 * same thing on any emulator.
 */
class FoodDayEntryCardLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** PRD_FOOD 15's 80-character ceiling, at PRD_FOOD 18's largest text size. */
    @Test
    fun theLongestNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setDay(fontScale = LargestFontScale)

        val lunch = drawnLines().first { it.title == FoodDayPreviewData.LONGEST_NAME }
        assertWordsUnbroken(layoutOf(lunch, lunch.title))
    }

    /** The short names went the same way, which is what said the title was not the problem. */
    @Test
    fun everyNameKeepsItsWordsWholeAtTwiceTheFontScale() {
        setDay(fontScale = LargestFontScale)

        drawnLines().forEach { line -> assertWordsUnbroken(layoutOf(line, line.title)) }
    }

    /**
     * The worst of it: `1 × serving` and `225 g` drawn one glyph per line, then cut short.
     *
     * A quantity is a single reading. It belongs on one line or on none — never on eleven.
     */
    @Test
    fun aQuantityStaysOnOneLineAtTwiceTheFontScale() {
        setDay(fontScale = LargestFontScale)

        drawnLines().forEach { line ->
            val amount = line.amountLabel ?: return@forEach
            val layout = layoutOf(line, amount)

            assertEquals(
                "«$amount» is drawn over ${layout.lineCount} lines",
                1,
                layout.lineCount,
            )
            assertNothingDropped(layout)
        }
    }

    /** The scale the card was already right at, and the one the fix had to leave alone. */
    @Test
    fun theOrdinaryFontScaleIsUntouched() {
        setDay(fontScale = 1f)

        drawnLines().forEach { line ->
            assertWordsUnbroken(layoutOf(line, line.title))
            line.amountLabel?.let { amount ->
                assertEquals(1, layoutOf(line, amount).lineCount)
            }
        }
    }

    // region harness

    /**
     * Asserts that [layout] never had to break a word to fit the width it was given.
     *
     * A paragraph breaks mid-word only when it is laid out narrower than its longest word, which
     * is precisely `minIntrinsicWidth`. The half-pixel is for the rounding between the float the
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

    /** Every journal line the populated day puts on the glass, in the order it draws them. */
    private fun drawnLines(): List<FoodDayEntryUiState> =
        previewDayState().slots.flatMap { it.entries }

    /**
     * The layout of [text] as drawn inside [line]'s card.
     *
     * The search is scoped to the card and runs on the unmerged tree: the card announces itself
     * whole through `clearAndSetSemantics`, so its own strings are only reachable there — and a
     * figure such as `≈ 420 kcal` is drawn twice on a day whose moment holds one line.
     */
    private fun layoutOf(line: FoodDayEntryUiState, text: String): TextLayoutResult {
        val tag = FoodTestTags.logEntry(line.id.value)
        compose.onNodeWithTag(FoodTestTags.DAY).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()

        val node = compose
            .onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode("no node drawing «$text» inside $tag")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«$text» reported no layout")
    }

    private fun setDay(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(NarrowestPhone).fillMaxHeight()) {
                        FoodDayScreen(
                            state = previewDayState(),
                            onPreviousDay = {},
                            onNextDay = {},
                            onOpenDatePicker = {},
                            onDismissDatePicker = {},
                            onDayPicked = {},
                            onAddToSlot = {},
                            onEditEntry = {},
                            onConfirmPlan = {},
                            onSwapPlan = {},
                            onDismissPlan = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
