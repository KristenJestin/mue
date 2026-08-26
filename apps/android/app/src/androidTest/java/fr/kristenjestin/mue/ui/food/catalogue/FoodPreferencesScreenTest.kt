package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The module's preferences sheet, and the one switch on it (PRD_FOOD 13.2, FR-FOOD-010).
 *
 * The handle is `FoodTestTags.HIDE_ENERGY_TOGGLE`, reserved before the screen existed. The tag
 * names the effect someone comes here for; the switch is worded `Show energy`, which is the name
 * PRD_FOOD 13.2 gives the preference and the way every other switch in the app reads — on when
 * the thing it names is happening.
 */
@RunWith(AndroidJUnit4::class)
class FoodPreferencesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val toggles = mutableListOf<Boolean>()

    @Test
    fun theSwitchIsOnWhenTheFiguresAreShown() {
        setPreferences(FoodPreferencesUiState(showEnergy = true))

        compose.onNodeWithText(FoodCatalogueMessages.SHOW_ENERGY_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.HIDE_ENERGY_TOGGLE).assertIsOn()
    }

    @Test
    fun theSwitchIsOffWhenTheFiguresAreHidden() {
        setPreferences(FoodPreferencesUiState(showEnergy = false))

        compose.onNodeWithTag(FoodTestTags.HIDE_ENERGY_TOGGLE).assertIsOff()
    }

    @Test
    fun turningItOffIsReported() {
        setPreferences(FoodPreferencesUiState(showEnergy = true))

        compose.onNodeWithTag(FoodTestTags.HIDE_ENERGY_TOGGLE).performClick()

        assertEquals(listOf(false), toggles)
    }

    /** The explanation is on the card: turning the numbers off breaks nothing else. */
    @Test
    fun theSwitchSaysWhatItDoesAndWhatItDoesNot() {
        setPreferences(FoodPreferencesUiState(showEnergy = true))

        compose.onNodeWithText(FoodCatalogueMessages.SHOW_ENERGY_BODY).assertIsDisplayed()
    }

    /** PRD_FOOD 18: the whole card is the target, so it is far above the 48 dp minimum. */
    @Test
    fun theSwitchClearsTheTouchMinimum() {
        setPreferences(FoodPreferencesUiState(showEnergy = true))

        val height = compose.onNodeWithTag(FoodTestTags.HIDE_ENERGY_TOGGLE)
            .getUnclippedBoundsInRoot()
            .height

        assertTrue("the switch row is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun setPreferences(state: FoodPreferencesUiState) {
        compose.setContent {
            MueTheme {
                FoodPreferencesScreen(
                    state = state,
                    onShowEnergyChange = { toggles += it },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }
}
