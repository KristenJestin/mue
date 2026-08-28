package fr.kristenjestin.mue.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import fr.kristenjestin.mue.ui.components.MueHeaderChip
import fr.kristenjestin.mue.ui.components.MueScaffoldTestTags
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.food.FoodRoute
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The header is the same on all five tabs, so nothing but the screen changes when the tab does.
 *
 * `MUE` is drawn identically by every tab at the same place, so the eye takes it for part of the
 * window rather than part of the page — and a fixed thing that shifts by a few pixels reads as the
 * whole page lurching. That is what was reported: *"le bouton settings dans food pousse toujours
 * tout le truc, et du coup c'est pas beau au switch de tab parce que tout le tab se déplace."*
 *
 * ## What was wrong, measured
 *
 * `MueScreenScaffold`'s header row was `heightIn(min = 40.dp)`, which held for exactly as long as
 * every screen's trailing slot was a chip. The Food catalogue's was a *button*, and a button is
 * [fr.kristenjestin.mue.ui.theme.MueMinTouchTarget] — 48 dp — because PRD 15 says anything
 * tappable is. A 48 dp child in a 40 dp row grows the row, and this test measured the result on
 * `Foods` and on no other screen in the app:
 *
 * ```
 *   Day        wordmark top 73.52 dp, content top 170.67 dp
 *   Recipes    wordmark top 73.52 dp, content top 170.67 dp
 *   Foods      wordmark top 77.71 dp, content top 178.67 dp
 * ```
 *
 * The wordmark 4.19 dp down — it is centred in the row, so it moves half of what the row grows —
 * and everything under it 8 dp down. Sizing the row at the touch minimum instead means the tallest
 * control it may legally hold is exactly the height it already is, so no trailing slot can push it
 * again.
 *
 * ## The button has since gone, and the floor has not
 *
 * `Food preferences` moved to `Profile`, taking the wrench with it, so no trailing slot in the app
 * is taller than a chip today and the five tabs would agree at 40 dp again. The floor stays
 * because it was never only about that button: `MueSubScreenScaffold`'s header row is a 48 dp back
 * control between the same paddings, so at 40 dp a tab and a screen reached from a tab opened
 * their content 8 dp apart and every push stepped the page. That seam had nobody measuring it
 * until [aSubScreenOpensItsContentWhereATabDoes], which is now the test that fails if the line
 * goes back.
 *
 * ## The 60 dp this test does *not* claim away
 *
 * Food's content genuinely begins lower than the other four tabs', by the height of the view
 * switcher and the gap under it:
 *
 * ```
 *   Entry / Progress / Activity / Profile   content top 110.48 dp
 *   Food                                    content top 170.67 dp
 * ```
 *
 * That 60.19 dp is a control the other tabs do not have, not a defect, and it cannot be removed
 * without either reserving 60 dp of emptiness on four screens that have nothing to put in it or
 * taking the switcher away — which is the owner's call and not this test's. So what is asserted
 * instead is the thing that makes it *legible*: [theSwitcherBeginsWhereEveryOtherTabsContentDoes]
 * pins the band to start exactly where the other four tabs' content starts, which is the same as
 * saying the header above it is identical on all five. If a second tab ever grows a band, or this
 * one grows a second row, that assertion is what notices.
 *
 * ## Why measured and not looked at
 *
 * Nothing in the semantics tree changes when a row grows by eight pixels. Every string is present,
 * every control is displayed, `assertIsDisplayed` passed on all five tabs before and after. Only
 * the geometry differed, so only the geometry can be asserted.
 */
class MueTabHeaderAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    /** The landmark, on all five tabs. This is the assertion the complaint was about. */
    @Test
    fun everyTabDrawsItsWordmarkAtTheSameHeight() {
        compose.setContent { MueTheme { MueApp() } }

        val measured = MueDestination.entries.associate { destination ->
            compose.onNodeWithText(destination.label).performClick()
            compose.waitForIdle()
            destination.label to headerGeometry()
        }

        assertWordmarksAgree(measured)
    }

    /**
     * The three views inside the Food tab, where the offending control actually sits.
     *
     * Switching view is a tab change by another name — same scaffold, same wordmark — and it is
     * the switch on which the 8 dp showed without ever leaving the tab. Here the content tops must
     * agree too: all three views raise the same scaffold with the same band, so there is nothing
     * left that could legitimately differ.
     */
    @Test
    fun everyFoodViewOpensItsContentAtTheSameHeight() {
        compose.setContent { MueTheme { MueApp() } }
        openFood()

        val measured = FoodRoute.SWITCHABLE.associate { view ->
            compose.onNodeWithTag(FoodTestTags.view(view)).performClick()
            compose.waitForIdle()
            view.label to headerGeometry()
        }

        assertWordmarksAgree(measured)
        assertContentTopsAgree(measured)
    }

    /** The four tabs with no band of their own open their content at one height. */
    @Test
    fun theTabsWithoutABandOpenTheirContentAtTheSameHeight() {
        compose.setContent { MueTheme { MueApp() } }

        val measured = MueDestination.entries
            .filterNot { it == MueDestination.FOOD }
            .associate { destination ->
                compose.onNodeWithText(destination.label).performClick()
                compose.waitForIdle()
                destination.label to headerGeometry()
            }

        assertContentTopsAgree(measured)
    }

    /**
     * Food's extra height is the switcher and nothing else.
     *
     * The band is allowed to push Food's content down; what is *not* allowed is for the header
     * above the band to differ from the other tabs'. Anchoring on the switcher's own top edge says
     * exactly that, and says it without writing down a dp: whatever the band's height turns out to
     * be at this density and text size, it has to begin where Entry's content begins.
     */
    @Test
    fun theSwitcherBeginsWhereEveryOtherTabsContentDoes() {
        compose.setContent { MueTheme { MueApp() } }

        compose.onNodeWithText(MueDestination.ENTRY.label).performClick()
        compose.waitForIdle()
        val withoutABand = headerGeometry().contentTop

        openFood()
        val switcher = compose.onNodeWithTag(FoodTestTags.VIEW_SWITCHER).getUnclippedBoundsInRoot()
        val withABand = headerGeometry().contentTop

        assertEquals(
            "the Food tab's switcher does not begin where Entry's content begins — the header " +
                "above it differs, which is a step the eye reads at every switch into Food " +
                "(switcher top ${switcher.top}, Entry content top $withoutABand)",
            withoutABand,
            switcher.top,
        )
        assertTrue(
            "Food's content begins at $withABand, above the bottom of its own switcher " +
                "(${switcher.bottom}) — the band and the content overlap",
            withABand >= switcher.bottom,
        )
        println(
            "food band: switcher ${switcher.top}..${switcher.bottom}, " +
                "content $withoutABand without a band, $withABand with one",
        )
    }

    /**
     * A screen reached *from* a tab opens its content where the tab's does.
     *
     * This is the assertion that decides whether `MueScreenScaffold`'s 48 dp floor is still
     * earned now that the control it was raised for has gone to `Profile`. It is: the floor was
     * about the Food catalogue's wrench, but the *number* is `MueSubScreenScaffold`'s, whose
     * header row is a 48 dp back control between the same paddings. At 40 dp the two scaffolds
     * disagreed by eight, so every push — `Foods` into `Food editor`, `Activity` into
     * `Log activity`, `Profile` into `Server settings` — stepped the page by the same amount the
     * complaint was about, in the other direction. Nobody had measured that seam, so nothing
     * noticed; reverting the floor now would put it back.
     *
     * The two scaffolds are raised directly rather than through [MueApp], because what is being
     * measured is the shells and not any screen's use of them: one of them shows nothing at all,
     * which is the point — a difference this finds cannot be blamed on content.
     */
    @Test
    fun aSubScreenOpensItsContentWhereATabDoes() {
        val subScreen = mutableStateOf(false)
        compose.setContent {
            MueTheme {
                if (subScreen.value) {
                    MueSubScreenScaffold(
                        title = "Food preferences",
                        onNavigateBack = {},
                        navigationIcon = {},
                        content = {},
                    )
                } else {
                    MueScreenScaffold(
                        trailing = { MueHeaderChip("Health profile") },
                        content = {},
                    )
                }
            }
        }

        val tab = compose.onNodeWithTag(MueScaffoldTestTags.CONTENT)
            .getUnclippedBoundsInRoot()
            .top

        subScreen.value = true
        compose.waitForIdle()

        val sub = compose.onNodeWithTag(MueScaffoldTestTags.SUB_SCREEN_CONTENT)
            .getUnclippedBoundsInRoot()
            .top

        println("header seam: tab content top $tab, sub-screen content top $sub")
        assertEquals(
            "a sub-screen opens its content at $sub where a tab opens it at $tab — the two " +
                "header rows are different heights, so the page steps on every push. " +
                "`MueScreenScaffold`'s row has to keep its `heightIn(min = MueMinTouchTarget)`: " +
                "`MueSubScreenScaffold`'s back control is that tall and cannot be shorter.",
            tab,
            sub,
        )
    }

    // region harness

    private fun openFood() {
        compose.onNodeWithText(MueDestination.FOOD.label).performClick()
        compose.waitForIdle()
    }

    /** Where the landmark sits, and where what is under it begins. */
    private data class HeaderGeometry(val wordmarkTop: Dp, val contentTop: Dp)

    private fun headerGeometry() = HeaderGeometry(
        wordmarkTop = compose.onNodeWithTag(MueScaffoldTestTags.WORDMARK)
            .getUnclippedBoundsInRoot().top,
        contentTop = compose.onNodeWithTag(MueScaffoldTestTags.CONTENT)
            .getUnclippedBoundsInRoot().top,
    )

    private fun assertWordmarksAgree(measured: Map<String, HeaderGeometry>) =
        assertAgree(measured, "wordmark", HeaderGeometry::wordmarkTop)

    private fun assertContentTopsAgree(measured: Map<String, HeaderGeometry>) =
        assertAgree(measured, "content", HeaderGeometry::contentTop)

    /**
     * Every screen measured agrees with the first one on one edge.
     *
     * Both edges are printed whichever one is being asserted, because which of the two moved is
     * what says *why*: the wordmark is centred in the header row, so it moves half as far as the
     * content does, and a failure reporting one without the other leaves the next reader to
     * work that out again.
     */
    private fun assertAgree(
        measured: Map<String, HeaderGeometry>,
        edge: String,
        of: (HeaderGeometry) -> Dp,
    ) {
        val report = measured.entries.joinToString(separator = "\n") { (name, geometry) ->
            "  %-10s wordmark top %s, content top %s".format(
                name,
                geometry.wordmarkTop,
                geometry.contentTop,
            )
        }
        println("header geometry\n$report")

        val (firstName, expected) = measured.entries.first()
        measured.forEach { (name, geometry) ->
            assertEquals(
                "`$name` puts its $edge at a different height from `$firstName` — the header row " +
                    "grew, and the page steps at the switch\n$report",
                of(expected),
                of(geometry),
            )
        }
    }

    // endregion
}
