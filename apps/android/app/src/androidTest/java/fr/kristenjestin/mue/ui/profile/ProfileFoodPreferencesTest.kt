package fr.kristenjestin.mue.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Profile`'s stack, now that it holds two sub-screens instead of one.
 *
 * This is the assertion the move is for: *"déplace juste le bouton de settings dans foods et
 * mets-le dans profile et c'est bon."* The door is a card in `Profile`'s own `Preferences`
 * section, beside `Haptic feedback`, and what it opens is the screen itself — not a push into the
 * Food tab's stack, which is why `FoodPreferencesScreen` came over with the button.
 *
 * The whole `ProfileScreen` is driven, ViewModels and all, because the stack is the thing under
 * test and it lives in that composable rather than in the stateless one every other `Profile`
 * test uses.
 */
@RunWith(AndroidJUnit4::class)
class ProfileFoodPreferencesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** PRD_FOOD 6.7's options, one tap from the tab that holds every other preference. */
    @Test
    fun thePreferencesSectionOpensTheFoodPreferences() {
        start()

        compose.onNodeWithTag(ProfileTestTags.FOOD_PREFERENCES).assertDoesNotExist()
        openFoodPreferences()

        compose.onNodeWithTag(ProfileTestTags.FOOD_PREFERENCES).assertIsDisplayed()
        compose.onNodeWithTag(ProfileTestTags.HIDE_ENERGY_TOGGLE).assertIsDisplayed()
    }

    /**
     * One exit, and it is the back arrow the rest of the app uses.
     *
     * `MueSubScreenScaffold` is the shell, so the screen names itself, carries a back control and
     * has no second way out — the arrangement every sub-screen in the app follows.
     */
    @Test
    fun theBackArrowReturnsToTheProfile() {
        start()
        openFoodPreferences()

        compose.onNodeWithContentDescription(FoodPreferencesMessages.BACK).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ProfileTestTags.FOOD_PREFERENCES).assertDoesNotExist()
        compose.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).assertExists()
    }

    /**
     * And so does the system's own gesture, before it ever reaches the tab selection.
     *
     * That is the `BackHandler` nested inside the shell's, which the stack already had for
     * `Server settings` and which now has to answer for a route rather than for a boolean. A
     * second boolean beside the first would have left this press with no single answer.
     */
    @Test
    fun theSystemBackReturnsToTheProfile() {
        start()
        openFoodPreferences()

        pressBack()

        compose.onNodeWithTag(ProfileTestTags.FOOD_PREFERENCES).assertDoesNotExist()
        compose.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).assertExists()
    }

    // region harness

    private fun start() {
        compose.setContent { MueTheme { ProfileScreen(Modifier.fillMaxSize()) } }
        compose.waitForIdle()
    }

    private fun openFoodPreferences() {
        compose.onNodeWithTag(ProfileTestTags.OPEN_FOOD_PREFERENCES)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
    }

    /**
     * A back press has to land on a settled tree: dispatching it in the same frame as the tap that
     * opened the screen would reach a handler that has not been told it is enabled yet.
     */
    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    // endregion
}
