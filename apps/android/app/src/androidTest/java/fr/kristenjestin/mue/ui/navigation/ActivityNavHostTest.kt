package fr.kristenjestin.mue.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.ui.activity.ActivityNavHost
import fr.kristenjestin.mue.ui.activity.ActivityRoute
import fr.kristenjestin.mue.ui.activity.ActivityStack
import fr.kristenjestin.mue.ui.activity.rememberActivityStack
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val PUSH_HISTORY = "push history"
private const val PUSH_LOG = "push log"
private const val PUSH_EDIT = "push edit"
private const val PUSH_STRENGTH = "push strength"
private const val SAVE_FROM_STRENGTH = "save from strength"

private val EditedSession = ActivityId("7b6a2f1e-0000-4000-8000-000000000001")

/** The stand-in name of a route, so a test can tell which screen answered. */
private fun ActivityRoute.testName(): String = when (this) {
    ActivityRoute.Dashboard -> "Dashboard"
    ActivityRoute.History -> "History"
    ActivityRoute.Strength -> "Strength"
    is ActivityRoute.Log -> "Log ${sessionId?.value ?: "new"}"
}

/**
 * The Activity tab's own back stack (PRD_ACTIVITIES 7), driven with stand-in screens so that
 * nothing here waits on the four real ones or on the database behind them.
 */
class ActivityNavHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Every stand-in offers the same pushes, so any screen can open any other. */
    @Composable
    private fun StandInScreen(route: ActivityRoute, stack: ActivityStack) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Column {
            Text("${route.testName()} body")
            Text(
                text = "${route.testName()} taps $taps",
                modifier = Modifier.clickable { taps++ },
            )
            Text(PUSH_HISTORY, Modifier.clickable { stack.push(ActivityRoute.History) })
            Text(PUSH_LOG, Modifier.clickable { stack.push(ActivityRoute.Log(sessionId = null)) })
            Text(PUSH_EDIT, Modifier.clickable { stack.push(ActivityRoute.Log(EditedSession)) })
            Text(PUSH_STRENGTH, Modifier.clickable { stack.push(ActivityRoute.Strength) })
            Text(SAVE_FROM_STRENGTH, Modifier.clickable { stack.pop(count = 2) })
        }
    }

    @Composable
    private fun ActivityTab() {
        val stack = rememberActivityStack()
        ActivityNavHost(stack = stack) { route -> StandInScreen(route, stack) }
    }

    private fun setHost() {
        composeRule.setContent { MueTheme { ActivityTab() } }
    }

    /** The whole shell, so back and the tab bar are the real ones. */
    private fun setShell() {
        composeRule.setContent {
            MueTheme {
                MueNavigationHost { destination ->
                    if (destination == MueDestination.ACTIVITY) {
                        ActivityTab()
                    } else {
                        Text("${destination.label} body")
                    }
                }
            }
        }
    }

    /**
     * A back press has to land on a settled tree: dispatching it in the same frame as the tap
     * that opened the screen would reach a handler that has not been told it is enabled yet.
     */
    private fun pressBack() {
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun opensOnTheDashboard() {
        setHost()

        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
    }

    @Test
    fun aPushShowsTheNewScreenAndAPopComesBack() {
        setHost()

        composeRule.onNodeWithText(PUSH_HISTORY).performClick()
        composeRule.onNodeWithText("History body").assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard body").assertDoesNotExist()

        pressBack()
        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
    }

    @Test
    fun theStackGoesAsDeepAsTheDetailedEditor() {
        setHost()

        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText("Log new body").assertIsDisplayed()

        composeRule.onNodeWithText(PUSH_STRENGTH).performClick()
        composeRule.onNodeWithText("Strength body").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithText("Log new body").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
    }

    /** PRD 9.1: the editor and the form hold one session, so a save there closes both. */
    @Test
    fun savingFromTheDetailedEditorLeavesTheFormBehindToo() {
        setHost()

        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText(PUSH_STRENGTH).performClick()
        composeRule.onNodeWithText(SAVE_FROM_STRENGTH).performClick()

        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
    }

    @Test
    fun theDashboardIsNeverPopped() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))

        stack.pop(count = 4)

        assertEquals(listOf(ActivityRoute.Dashboard), stack.entries)
    }

    /** A screen underneath keeps what it held; the one that was popped starts over. */
    @Test
    fun aPoppedScreenComesBackFresh() {
        setHost()

        composeRule.onNodeWithText("Dashboard taps 0").performClick()
        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText("Log new taps 0").performClick()
        composeRule.onNodeWithText("Log new taps 1").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithText("Dashboard taps 1").assertIsDisplayed()

        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText("Log new taps 0").assertIsDisplayed()
    }

    @Test
    fun theStackSurvivesProcessDeath() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { MueTheme { ActivityTab() } }

        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText(PUSH_STRENGTH).performClick()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Strength body").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("Log new body").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
    }

    /** An edited session is a route argument, so the id has to cross the bundle with it. */
    @Test
    fun anEditedSessionKeepsItsIdAcrossProcessDeath() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { MueTheme { ActivityTab() } }

        composeRule.onNodeWithText(PUSH_EDIT).performClick()
        composeRule.onNodeWithText("Log ${EditedSession.value} body").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Log ${EditedSession.value} body").assertIsDisplayed()
    }

    @Test
    fun backFromASubScreenStaysInsideTheTab() {
        setShell()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText(PUSH_HISTORY).performClick()

        pressBack()
        composeRule.onNodeWithText("Dashboard body").assertIsDisplayed()
        composeRule.onNodeWithText("Activity").assertIsSelected()

        // Only once the tab is back at its root does back belong to the shell again.
        pressBack()
        composeRule.onNodeWithText("Entry body").assertIsDisplayed()
        composeRule.onNodeWithText("Entry").assertIsSelected()
    }

    /** Contract decision 2: no module screen hides the bar, not even the two forms. */
    @Test
    fun theBarStaysVisibleOnEverySubScreen() {
        setShell()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText(PUSH_STRENGTH).performClick()

        composeRule.onNodeWithText("Strength body").assertIsDisplayed()
        MueDestination.entries.forEach { tab ->
            composeRule.onNodeWithText(tab.label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Activity").assertIsSelected()
    }

    /**
     * PRD 8 again, one level down: the stack animates inside the tab, and the bar is not part
     * of what moves.
     */
    @Test
    fun theBarHoldsStillWhileTheStackRises() {
        setShell()
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        val barAtRest = MueDestination.entries.map { tabBounds(it) }
        val dashboardAtRest = bodyTop("Dashboard")

        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(MueMotion.ActivityOpenMillis / 2L)

        composeRule.onNodeWithText("Log new body").assertExists()
        assertTrue(
            "the screen underneath should have risen",
            bodyTop("Dashboard") < dashboardAtRest,
        )
        assertEquals(barAtRest, MueDestination.entries.map { tabBounds(it) })
    }

    private fun tabBounds(destination: MueDestination) =
        composeRule.onNodeWithText(destination.label).getUnclippedBoundsInRoot()

    private fun bodyTop(screen: String) =
        composeRule.onNodeWithText("$screen body").getUnclippedBoundsInRoot().top

    /** Leaving a form through a tab must not throw it away (PRD_ACTIVITIES 7). */
    @Test
    fun aSubScreenIsStillOpenAfterATripThroughAnotherTab() {
        setShell()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText(PUSH_LOG).performClick()
        composeRule.onNodeWithText("Log new taps 0").performClick()

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithText("Profile body").assertIsDisplayed()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Log new body").assertIsDisplayed()
        composeRule.onNodeWithText("Log new taps 1").assertIsDisplayed()
    }
}
