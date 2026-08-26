package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.ui.activity.LogActivityMessages
import fr.kristenjestin.mue.ui.navigation.MueDestination
import fr.kristenjestin.mue.ui.progress.DELETE_MEASUREMENT
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The narrowest phone the app supports, where five tabs get 72 dp each. */
private val NarrowestPhone = 360.dp

/** The largest text size the system offers. */
private const val LargestFontScale = 2f

/** The bar the app actually ships, labels and all — PRD copy, not a fixture. */
private val ShippedTabs = MueDestination.entries.map { MueTab(it.label, it.iconRes) }

/**
 * What the tab bar's labels actually draw, rather than what their semantics strings say.
 *
 * At 360 dp and the largest font scale the bar drew `Entry · Pro… · Acti… · Food · Pro…`:
 * **`Progress` and `Profile` both came out `Pro…`**, two tabs saying one word, and `Food Pro…`
 * ran together with no gutter between them. Not one assertion in the shell could see it.
 * `onNodeWithText("Progress")` matches the semantics string, which stays `Progress` however few
 * of its letters reach the glass — so the truncated bar passed every test the app had, including
 * the ones that click a tab *by that name*.
 *
 * These tests read the [TextLayoutResult] the label hands out and reconstruct the substring the
 * reader is actually shown: `getLineEnd(visibleEnd = true)` stops where the ellipsis begins. From
 * that, two things can finally be said —
 *
 * - a label is drawn **whole or not at all**, never as a stump;
 * - and no two tabs draw the same thing, which is the defect stated exactly.
 *
 * The last test is the other half of the bargain: dropping a label may not cost the tab its name,
 * its `Role.Tab` or its selected state, because a bar of five anonymous glyphs would be a worse
 * defect than the one being fixed.
 */
class MueBottomBarLabelTest {

    @get:Rule
    val compose = createComposeRule()

    /** The width the bar was already right at, and the one the fix had to leave alone. */
    @Test
    fun everyLabelIsDrawnWholeAtTheOrdinaryFontScale() {
        setBar(fontScale = 1f)

        ShippedTabs.forEach { tab ->
            assertEquals("«${tab.label}» is not drawn whole", tab.label, drawnLabel(tab))
        }
    }

    /**
     * The defect: `Progress` reaching the glass as `Pro…`.
     *
     * A label that will not fit is not drawn at all, so the only two answers allowed here are the
     * whole word and nothing.
     */
    @Test
    fun noLabelIsDrawnAsAStumpAtTwiceTheFontScale() {
        setBar(fontScale = LargestFontScale)

        ShippedTabs.forEach { tab ->
            val drawn = drawnLabel(tab)
            assertEquals(
                "«${tab.label}» reaches the glass as «$drawn»",
                tab.label,
                drawn ?: tab.label,
            )
        }
    }

    /** Two tabs drawing one word is a bar that cannot be read, whatever it announces. */
    @Test
    fun noTwoTabsDrawTheSameLabelAtTwiceTheFontScale() {
        setBar(fontScale = LargestFontScale)

        val drawn = ShippedTabs.mapNotNull { drawnLabel(it) }

        assertEquals("the bar draws $drawn", drawn.distinct(), drawn)
    }

    /**
     * A dropped label is still a name: PRD_ACTIVITIES 15, and the whole reason the label may go.
     *
     * The tab keeps its `Role.Tab` and its selected state either way — both sit on `selectable`,
     * which the label never touched.
     */
    @Test
    fun everyTabKeepsItsNameItsRoleAndItsSelectionWithoutLabels() {
        val selected = MueDestination.PROGRESS.ordinal
        setBar(fontScale = LargestFontScale, selectedIndex = selected)

        ShippedTabs.forEachIndexed { index, tab ->
            val node = compose.onNode(
                isSelectable() and (hasContentDescription(tab.label) or hasText(tab.label)),
            )
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            if (index == selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    // region harness

    /**
     * The glyphs [tab]'s label puts on the glass, or `null` when the bar draws no label at all.
     *
     * The search runs on the unmerged tree so it finds the text node itself rather than the tab
     * that merges it — the merged tab carries the same string whether or not any of it is drawn,
     * which is precisely why the shell's assertions were blind to this.
     */
    private fun drawnLabel(tab: MueTab): String? {
        val node = compose
            .onAllNodes(hasText(tab.label), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull { it.config.contains(SemanticsActions.GetTextLayoutResult) }
            ?: return null

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.firstOrNull() ?: return null

        return tab.label.take(layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun setBar(
        fontScale: Float,
        width: Dp = NarrowestPhone,
        selectedIndex: Int = 0,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(width)) {
                        MueBottomBar(
                            tabs = ShippedTabs,
                            selectedIndex = selectedIndex,
                            onTabSelected = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion

    // region shared components under the same squeeze

    /*
     * The tab bar was the first place a doubled font scale was caught drawing one word where two
     * were meant, but it was never the only one. The three components below are the rest of the
     * app's shared chrome that a font-scale sweep found breaking the same way, and they are tested
     * here because the defect and the instrument are identical: a label pinned to one line, in a
     * slot measured for the ordinary text size, ellipsised or cut mid-word once the type doubles —
     * and a semantics string that goes on reporting the whole label, so every `onNodeWithText` in
     * the suite stays green.
     *
     * Each pair is the same bargain the tab bar's tests make: one test at the largest text size,
     * which is the defect, and one at the ordinary size, which is the drawing that shipped and
     * must not move.
     */

    /**
     * A button's label, which had nowhere to widen to and so was losing its end.
     *
     * `Export weight data` came out `Export weight …` and `Delete measurement` came out
     * `Delete measurem…` — the second a destructive action whose *object* was the part that went,
     * which is the difference between deleting a measurement and deleting something unnamed. The
     * slab is already full width, so a second line is the only room there is, and a taller button
     * is what a larger text size asks for.
     */
    @Test
    fun everyButtonLabelIsDrawnWholeAtTwiceTheFontScale() {
        setButtons(fontScale = LargestFontScale)

        ShippedButtonLabels.forEach { label -> assertWordsUnbroken(layoutOf(label)) }
    }

    /** The size the buttons were already right at: every label on a single line. */
    @Test
    fun everyButtonLabelKeepsOneLineAtTheOrdinaryFontScale() {
        setButtons(fontScale = 1f)

        ShippedButtonLabels.forEach { label ->
            val layout = layoutOf(label)
            assertEquals("«" + label + "» is drawn over " + layout.lineCount + " lines", 1, layout.lineCount)
            assertNothingDropped(layout)
        }
    }

    /**
     * The six preset tiles, four of which were cut in the middle of their word.
     *
     * Three across is 96 dp of a 360 dp screen and a tile spends 24 dp of it on padding, so at the
     * largest text size `Treadmill` alone wanted more than the 72 dp left: `Tread` over `mill …`,
     * `Outd` over `oor …`, `Cycli` over `ng`, `Stren` over `gth …`, on the first control of both
     * `Log activity` and `Start activity`. The grid gives way to two across and then to one until
     * the longest word fits, which is what [MueChoiceGrid] measures rather than guesses.
     */
    @Test
    fun everyPresetTileKeepsItsWordsWholeAtTwiceTheFontScale() {
        setPresetGrid(fontScale = LargestFontScale)

        PresetLabels.forEach { label -> assertWordsUnbroken(layoutOf(label)) }
    }

    /**
     * The ordinary size, where the six presets still sit three across.
     *
     * Six tiles over three columns is two rows, so the number of distinct top edges *is* the
     * number of rows — which says the grid has not given way at a scale where it never had to,
     * without naming a single `dp`.
     */
    @Test
    fun thePresetGridStaysThreeAcrossAtTheOrdinaryFontScale() {
        setPresetGrid(fontScale = 1f)

        PresetLabels.forEach { label -> assertWordsUnbroken(layoutOf(label)) }

        val rows = PresetLabels.indices
            .map { index ->
                compose.onNodeWithTag(presetTag(index)).fetchSemanticsNode().boundsInRoot.top
            }
            .distinct()
        assertEquals(
            "the six presets are drawn over " + rows.size + " rows rather than two",
            2,
            rows.size,
        )
    }

    /**
     * Entry's date row, where the field's own name and the date it holds were *both* cut.
     *
     * At a doubled font scale on a 360 dp phone the row read `Measureme…` over `August 26, …` with
     * `Change` still beside it — on the one control that says which day is about to be recorded.
     * The same row carries `Start time · optional` on the activity form. Both ceilings go, and the
     * trailing action is measured rather than trusted, so it drops under the value when the two no
     * longer fit abreast.
     */
    @Test
    fun aDateRowKeepsItsLabelItsValueAndItsActionWholeAtTwiceTheFontScale() {
        setDateRows(fontScale = LargestFontScale)

        DateRowStrings.forEach { text -> assertWordsUnbroken(layoutOf(text)) }
    }

    /**
     * The ordinary size, where the label sits on one line and `Change` stays beside the value.
     *
     * Abreast is the drawing that shipped, and `MueSplitRow` stacks only when it must, so this is
     * the half of the bargain that says the fix cost the ordinary scale nothing.
     */
    @Test
    fun aDateRowKeepsItsActionAbreastAtTheOrdinaryFontScale() {
        setDateRows(fontScale = 1f)

        DateRowStrings.forEach { text ->
            val layout = layoutOf(text)
            assertEquals("«" + text + "» is drawn over " + layout.lineCount + " lines", 1, layout.lineCount)
            assertNothingDropped(layout)
        }

        val value = compose.onNodeWithTag(DateValueTag).fetchSemanticsNode().boundsInRoot
        val change = compose.onNodeWithTag(ChangeTag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the change action dropped under the date at the ordinary scale",
            change.left >= value.right - 0.5f,
        )
    }

    /**
     * The effort slider's own label, which `Strength session` uses to say the field is optional.
     *
     * It was pinned to one line, so at the largest text size `Perceived effort · optional` came
     * out `Perceived effort · optio…` — the word that went is the one telling the reader they may
     * skip the control. The row sits alone above the slider, so a second line costs nothing.
     */
    @Test
    fun theEffortLabelSaysItIsOptionalAtTwiceTheFontScale() {
        setEffortSliders(fontScale = LargestFontScale)

        EffortLabels.forEach { label -> assertWordsUnbroken(layoutOf(label)) }
    }

    /** The size the slider was already right at: each label on a single line. */
    @Test
    fun theEffortLabelKeepsOneLineAtTheOrdinaryFontScale() {
        setEffortSliders(fontScale = 1f)

        EffortLabels.forEach { label ->
            val layout = layoutOf(label)
            assertEquals("«" + label + "» is drawn over " + layout.lineCount + " lines", 1, layout.lineCount)
            assertNothingDropped(layout)
        }
    }

    // endregion

    // region harness for the shared components

    /**
     * The layout of the single node drawing [text], as the renderer laid it out.
     *
     * The unmerged tree is required: the merged parent carries the same string whether or not any
     * of it reaches the glass, which is exactly why the shell's assertions were blind to this.
     */
    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose
            .onAllNodes(hasText(text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull { it.config.contains(SemanticsActions.GetTextLayoutResult) }
            ?: error("nothing draws «" + text + "»")

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("«" + text + "» reported no layout")
    }

    /**
     * Asserts that [layout] never had to break a word to fit the width it was given.
     *
     * A paragraph breaks mid-word only when it is laid out narrower than its longest word, which
     * is precisely `minIntrinsicWidth`. The half-pixel is the rounding between the float the
     * paragraph reports and the integer it was measured at. `hasVisualOverflow` is deliberately
     * not used — it compares the paragraph's constraint with the node's measured size and goes
     * true for text that is merely narrower than its slot, which under a `FlowRow` is most of it.
     */
    private fun assertWordsUnbroken(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        val longestWord = layout.multiParagraph.minIntrinsicWidth
        val drawnAt = layout.size.width

        assertTrue(
            "«" + text + "» is drawn " + drawnAt + " px wide but its longest word needs " +
                longestWord + " px, so it breaks mid-word over " + layout.lineCount + " lines",
            longestWord <= drawnAt + 0.5f,
        )
        assertNothingDropped(layout)
    }

    /** Asserts the reader is shown the whole string rather than an ellipsis of it. */
    private fun assertNothingDropped(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        val drawn = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)

        assertEquals(
            "«" + text + "» is cut short after " + drawn + " of its " + text.length + " characters",
            text.length,
            drawn,
        )
    }

    private fun setPresetGrid(fontScale: Float) = setNarrowContent(fontScale) {
        MueChoiceGrid(labels = PresetLabels, maxColumns = PresetsPerRow) { index ->
            MueChoiceCard(
                label = PresetLabels[index],
                selected = false,
                onClick = {},
                modifier = Modifier.weight(1f).testTag(presetTag(index)),
            )
        }
    }

    private fun setButtons(fontScale: Float) = setNarrowContent(fontScale) {
        Column {
            MuePrimaryButton(label = DELETE_MEASUREMENT, onClick = {})
            MueSecondaryButton(label = ExportLabel, onClick = {})
        }
    }

    /**
     * The slider as `Strength session` actually mounts it, not as it is easiest to mount.
     *
     * Two things the shipped call site adds take width away from the label, and without both the
     * harness is wider than the screen and sees no defect: the gauge icon and its gutter, and the
     * sub-screen scaffold's own horizontal padding. A first version of this test omitted them and
     * passed with the defect still in place — which is the whole failure mode these tests exist
     * to catch, arriving in the test itself.
     */
    private fun setEffortSliders(fontScale: Float) = setNarrowContent(fontScale) {
        Column(Modifier.padding(horizontal = MueTheme.spacing.screenHorizontal)) {
            EffortLabels.forEach { label ->
                MueEffortSlider(
                    value = null,
                    onValueChange = {},
                    label = label,
                    icon = { MuePreviewIcon(MuePreviewGlyph.DOT, size = 14.dp) },
                )
            }
        }
    }

    private fun setDateRows(fontScale: Float) = setNarrowContent(fontScale) {
        Column {
            MueFieldContainer(
                label = MeasurementDateLabel,
                trailing = {
                    MueText(
                        Change,
                        MueTheme.typography.label,
                        modifier = Modifier.testTag(ChangeTag),
                    )
                },
            ) {
                MueText(
                    MeasurementDateValue,
                    MueTheme.typography.bodyStrong,
                    modifier = Modifier.testTag(DateValueTag),
                )
            }
            MueFieldContainer(label = LogActivityMessages.START_TIME_LABEL) {
                MueText(StartTimeValue, MueTheme.typography.bodyStrong)
            }
        }
    }

    /** The narrowest phone the app supports, at [fontScale], with the app's own theme. */
    private fun setNarrowContent(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Box(Modifier.width(NarrowestPhone)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}

/** PRD copy for the two loud buttons a font-scale sweep caught losing their ends. */
private const val ExportLabel = "Export weight data"

/** Both labels the shipped callers pass to [MueEffortSlider]. */
private val EffortLabels = listOf("Perceived effort", "Perceived effort · optional")

/** Entry's date row, as `EntryScreen` writes it. */
private const val MeasurementDateLabel = "Measurement date"
private const val MeasurementDateValue = "August 26, 2026"
private const val Change = "Change"

/** A start time as the activity form formats one. */
private const val StartTimeValue = "9:15 AM"

/** Both screens draw the presets three across before anything gives way. */
private const val PresetsPerRow = 3

private const val DateValueTag = "date-value"
private const val ChangeTag = "date-change"

private fun presetTag(index: Int): String = "preset-" + index

/** The two loud buttons, by the words their screens actually put on them. */
private val ShippedButtonLabels = listOf(ExportLabel, DELETE_MEASUREMENT)

/** The six presets of PRD 8.5, in the order both screens draw them. */
private val PresetLabels = ActivityPreset.entries.map { it.label }

/** Entry's date row and the activity form's start time, as the two screens write them. */
private val DateRowStrings = listOf(
    MeasurementDateLabel,
    MeasurementDateValue,
    Change,
    LogActivityMessages.START_TIME_LABEL,
)
