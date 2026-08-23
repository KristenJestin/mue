package fr.kristenjestin.mue.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Rule
import org.junit.Test

/** Titles of the three screens; the tab is only proven if its own screen answers. */
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

        TITLES.forEach { (destination, title) ->
            composeRule.onNodeWithContentDescription(destination.label).performClick()
            composeRule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun theScreenTitleFollowsTheSelectedTab() {
        composeRule.setContent { MueTheme { MueApp() } }

        composeRule.onNodeWithContentDescription("Profile").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TITLES.getValue(MueDestination.ENTRY)).assertDoesNotExist()
        composeRule.onNodeWithText(TITLES.getValue(MueDestination.PROFILE)).assertIsDisplayed()
    }
}
