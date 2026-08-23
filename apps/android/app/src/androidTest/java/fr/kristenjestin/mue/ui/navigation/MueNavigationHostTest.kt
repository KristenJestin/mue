package fr.kristenjestin.mue.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Mechanics of the tab shell (PRD 8), driven with stand-in screens so that nothing here
 * depends on the database or on what the three real screens happen to display.
 */
class MueNavigationHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setHost(reduceMotion: Boolean = false) {
        composeRule.setContent {
            MueTheme(reduceMotion = reduceMotion) {
                MueNavigationHost { destination ->
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("${destination.label} body")
                        Text(
                            text = "${destination.label} taps $taps",
                            modifier = Modifier.clickable { taps++ },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun opensOnEntry() {
        setHost()

        composeRule.onNodeWithText("Entry body").assertIsDisplayed()
        composeRule.onNodeWithText("Entry").assertIsSelected()
    }

    @Test
    fun eachTabRendersItsOwnScreen() {
        setHost()

        MueDestination.entries.forEach { destination ->
            composeRule.onNodeWithText(destination.label).performClick()
            composeRule.onNodeWithText("${destination.label} body").assertIsDisplayed()
            composeRule.onNodeWithText(destination.label).assertIsSelected()
        }
    }

    /**
     * A contentDescription repeating the visible label would sit next to it under the same
     * merging parent, and TalkBack would announce the tab twice.
     */
    @Test
    fun aTabIsAnnouncedByItsLabelAlone() {
        setHost()

        MueDestination.entries.forEach { destination ->
            composeRule.onNodeWithText(destination.label).assert(
                SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)
            )
        }
    }

    @Test
    fun theBarIsPresentOnEveryTab() {
        setHost()

        MueDestination.entries.forEach { destination ->
            composeRule.onNodeWithText(destination.label).performClick()
            MueDestination.entries.forEach { tab ->
                composeRule.onNodeWithText(tab.label).assertIsDisplayed()
            }
        }
    }

    @Test
    fun theBarDoesNotMoveBetweenTabs() {
        setHost()
        val atLaunch = MueDestination.entries.map { bounds(it) }

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitForIdle()

        assertEquals(atLaunch, MueDestination.entries.map { bounds(it) })
    }

    @Test
    fun eachTabKeepsItsStateAcrossASwitch() {
        setHost()

        composeRule.onNodeWithText("Entry taps 0").performClick()
        composeRule.onNodeWithText("Entry taps 1").performClick()

        composeRule.onNodeWithText("Progress").performClick()
        composeRule.onNodeWithText("Progress taps 0").assertIsDisplayed()
        composeRule.onNodeWithText("Progress taps 0").performClick()

        composeRule.onNodeWithText("Entry").performClick()
        composeRule.onNodeWithText("Entry taps 2").assertIsDisplayed()

        composeRule.onNodeWithText("Progress").performClick()
        composeRule.onNodeWithText("Progress taps 1").assertIsDisplayed()
    }

    @Test
    fun onlyTheSelectedTabIsShown() {
        setHost()

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Profile body").assertIsDisplayed()
        composeRule.onNodeWithText("Entry body").assertDoesNotExist()
    }

    @Test
    fun theBarHoldsStillWhileTheContentSlides() {
        composeRule.mainClock.autoAdvance = false
        setHost()
        val barAtRest = MueDestination.entries.map { bounds(it) }
        val entryAtRest = bodyLeft(MueDestination.ENTRY)

        composeRule.onNodeWithText("Progress").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(MueMotion.TabChangeMillis / 2L)

        composeRule.onNodeWithText("Progress body").assertExists()
        assertTrue(
            "the outgoing tab should have slid left",
            bodyLeft(MueDestination.ENTRY) < entryAtRest,
        )
        assertEquals(barAtRest, MueDestination.entries.map { bounds(it) })
    }

    /** PRD 14: with animations reduced the tabs cross-fade in place, nothing travels. */
    @Test
    fun reducedMotionCrossFadesWithoutSliding() {
        composeRule.mainClock.autoAdvance = false
        setHost(reduceMotion = true)
        val entryAtRest = bodyLeft(MueDestination.ENTRY)

        composeRule.onNodeWithText("Progress").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(MueMotion.ReducedMillis / 2L)

        composeRule.onNodeWithText("Progress body").assertExists()
        assertEquals(entryAtRest, bodyLeft(MueDestination.ENTRY))
    }

    private fun bounds(destination: MueDestination) =
        composeRule.onNodeWithText(destination.label).getUnclippedBoundsInRoot()

    private fun bodyLeft(destination: MueDestination) =
        composeRule.onNodeWithText("${destination.label} body").getUnclippedBoundsInRoot().left
}
