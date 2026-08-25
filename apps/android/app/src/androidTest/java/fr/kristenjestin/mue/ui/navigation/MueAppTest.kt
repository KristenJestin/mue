package fr.kristenjestin.mue.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Rule
import org.junit.Test

/**
 * Titles of the screens that open a tab; the tab is only proven if its own screen answers.
 *
 * `Activity` has none yet: its dashboard is still a stub, so the fourth tab is proven by the
 * selection it takes and by the screens it hides. The title joins this map with the screen.
 */
private val TITLES = mapOf(
    MueDestination.ENTRY to "Where are you today?",
    MueDestination.PROGRESS to "Slowly, surely.",
    MueDestination.PROFILE to "Tracking shaped around you.",
)

/** The shell with the real screens behind it, on the device's real storage. */
class MueAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyTabShowsItsScreen() {
        composeRule.setContent { MueTheme { MueApp() } }

        MueDestination.entries.forEach { destination ->
            composeRule.onNodeWithText(destination.label).performClick()
            composeRule.onNodeWithText(destination.label).assertIsSelected()
            TITLES[destination]?.let { composeRule.onNodeWithText(it).assertIsDisplayed() }
        }
    }

    @Test
    fun theScreenTitleFollowsTheSelectedTab() {
        composeRule.setContent { MueTheme { MueApp() } }

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TITLES.getValue(MueDestination.ENTRY)).assertDoesNotExist()
        composeRule.onNodeWithText(TITLES.getValue(MueDestination.PROFILE)).assertIsDisplayed()
    }

    /** The Activity tab replaces the other screens even while its own is empty. */
    @Test
    fun theActivityTabTakesTheScreenOverFromItsNeighbours() {
        composeRule.setContent { MueTheme { MueApp() } }

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Activity").assertIsSelected()
        TITLES.values.forEach { title ->
            composeRule.onNodeWithText(title).assertDoesNotExist()
        }
    }
}
