package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.navigation.MueDestination
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
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
}
