package fr.kristenjestin.mue.ui.food.day

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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

    /**
     * PRD_FOOD 12's three actions, and the one of them that destroys a proposal.
     *
     * A third of 360 dp is 120 dp, and at a doubled font scale `Dismiss` alone wanted more than
     * the 94 dp that was left beside its glyph, so the word came out cut in its middle — `Dismi`
     * over `ss`. `onNodeWithText(DISMISS)` never saw it: the semantics string is the whole word
     * however the glyphs fall.
     */
    @Test
    fun aProposalKeepsEveryActionWordWholeAtTwiceTheFontScale() {
        setDay(fontScale = LargestFontScale)

        planActions().forEach { (tag, label) -> assertWordsUnbroken(layoutInside(tag, label)) }
    }

    /** The scale the proposal was already right at, and the one the fix had to leave alone. */
    @Test
    fun aProposalKeepsItsActionsOnOneLineAtTheOrdinaryFontScale() {
        setDay(fontScale = 1f)

        planActions().forEach { (tag, label) ->
            val layout = layoutInside(tag, label)
            assertEquals("«$label» is drawn over ${layout.lineCount} lines", 1, layout.lineCount)
            assertNothingDropped(layout)
        }
    }

    /**
     * The moment's heading, which broke where the journal lines under it had already been mended.
     *
     * `BREAKF` over `AST`: the heading's `Row` measured the unweighted total first and at
     * whatever it asked for, and at a doubled font scale `≈ 370 kcal` over `≈ 29.1 g protein`
     * left the name a ribbon it could only fit by cutting the word in half. One moment reading as
     * two, and `onNodeWithText("Breakfast")` could not tell — the semantics string is
     * [FoodDaySlotUiState.description] whatever the glyphs do.
     *
     * The heading's own text layout cannot be read back: it speaks through `clearAndSetSemantics`,
     * which takes `GetTextLayoutResult` with it. So the assertion is geometric, and says the same
     * thing without a magic number: **every moment's name is drawn on one line.** A name is a
     * single word, so a name that takes two lines is a name that has been cut in half — which is
     * exactly `BREAKF` over `AST`, and exactly what the shortest heading on the same screen,
     * drawn in the same style, gives the height of one line to compare against.
     *
     * Deliberately *not* asserted: that the total drops under the name. [MueSplitRow] stacks only
     * when it must, so `LUNCH` keeps its total abreast at this scale and is right to — demanding
     * the stack everywhere would be demanding a redesign rather than a fix.
     *
     * [MueSplitRow]: fr.kristenjestin.mue.ui.components.MueSplitRow
     */
    @Test
    fun noMomentHeadingIsBrokenAcrossLinesAtTwiceTheFontScale() {
        setDay(fontScale = LargestFontScale)

        assertEveryHeadingOnOneLine()
    }

    /** The ordinary scale, where the heading was already right: name and total abreast. */
    @Test
    fun aMomentHeadingKeepsItsTotalAbreastAtTheOrdinaryFontScale() {
        setDay(fontScale = 1f)

        assertEveryHeadingOnOneLine()
        headedSlots().forEach { slot ->
            val name = headingBounds(slot)
            val total = totalBounds(slot)

            assertTrue(
                "«${slot.label}» dropped its total onto a second line at the ordinary scale",
                total.left >= name.right - 0.5f,
            )
        }
    }

    // region harness

    /** The proposal's three actions, by the handle each is drawn under and the word on it. */
    private fun planActions(): List<Pair<String, String>> =
        previewDayState().slots.filter { it.plan != null }.flatMap { slot ->
            listOf(
                FoodTestTags.confirmPlan(slot.slot) to FoodDayMessages.I_ATE_THIS,
                FoodTestTags.swapPlan(slot.slot) to FoodDayMessages.SWAP,
                FoodTestTags.dismissPlan(slot.slot) to FoodDayMessages.DISMISS,
            )
        }

    /** The moments with a total to place beside their name — the only ones that can split. */
    private fun headedSlots(): List<FoodDaySlotUiState> =
        previewDayState().slots.filter { it.hasTotal }

    /**
     * Asserts that no moment's name has been laid out over more than one line.
     *
     * A moment is named by a single word, so the height of the shortest name on the screen — drawn
     * in the same style, at the same scale — is the height of one line, and any name taller than it
     * has been broken.
     */
    private fun assertEveryHeadingOnOneLine() {
        val drawn = headedSlots().associate { it.label to headingBounds(it).height }
        val oneLine = drawn.values.min()

        drawn.forEach { (label, height) ->
            assertEquals(
                "«$label» is drawn ${height / oneLine} lines tall where every other moment fits " +
                    "on one, so its word has been cut in half",
                oneLine.toDouble(),
                height.toDouble(),
                0.5,
            )
        }
    }

    /**
     * Where the moment's name is drawn.
     *
     * The heading speaks through `clearAndSetSemantics`, so its own string is unreachable and it
     * is found by the sentence it announces instead.
     */
    private fun headingBounds(slot: FoodDaySlotUiState): Rect =
        scrolledTo(FoodTestTags.slot(slot.slot))
            .onNode(
                hasAnyAncestor(hasTestTag(FoodTestTags.slot(slot.slot))) and
                    hasContentDescription(slot.description),
                useUnmergedTree = true,
            )
            .fetchSemanticsNode("no heading drawn for ${slot.label}")
            .boundsInRoot

    /** Where the moment's total is drawn. */
    private fun totalBounds(slot: FoodDaySlotUiState): Rect =
        scrolledTo(FoodTestTags.slot(slot.slot))
            .onNodeWithTag(FoodTestTags.slotTotal(slot.slot), useUnmergedTree = true)
            .fetchSemanticsNode("no total drawn for ${slot.label}")
            .boundsInRoot

    /** The layout of [text] as drawn inside the node handled [tag]. */
    private fun layoutInside(tag: String, text: String): TextLayoutResult {
        val node = scrolledTo(tag)
            .onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode("no node drawing «$text» inside $tag")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«$text» reported no layout")
    }

    /** The day, scrolled until [tag] is on the glass and settled. */
    private fun scrolledTo(tag: String): ComposeContentTestRule {
        compose.onNodeWithTag(FoodTestTags.DAY).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        return compose
    }

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
    private fun layoutOf(line: FoodDayEntryUiState, text: String): TextLayoutResult =
        layoutInside(FoodTestTags.logEntry(line.id.value), text)

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
