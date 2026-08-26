package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

private val SCALE = PairedScale(
    id = "a",
    displayName = "Bathroom scale",
    modelName = "Homebuds HB9027",
    driverId = "homebuds-hb9027",
    address = "FF:10:00:1F:52:C3",
    advertisedName = "Health Scale",
    lastSeenAt = Instant.parse("2026-08-25T07:12:00Z"),
    inRange = false,
)

/**
 * La fiche d'une balance : renommer, diagnostiquer, oublier (FR-SCALE-013, 014).
 *
 * Le brouillon du nom est hissé dans le test, comme l'exige la convention du dépôt : c'est l'écran
 * sans état qui est piloté, pas le ViewModel derrière lui.
 */
@RunWith(AndroidJUnit4::class)
class ScaleDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val savedNames = mutableListOf<String>()
    private var forgetRequested = 0
    private var forgetCancelled = 0
    private var forgetConfirmed = 0

    @Test
    fun theNameCanBeReplaced() {
        show(SCALE)

        compose.field(ScaleTestTags.RENAME_FIELD).performTextReplacement("Upstairs")
        compose.onNodeWithTag(ScaleTestTags.RENAME_CONFIRM).performScrollTo().performClick()

        assertEquals(listOf("Upstairs"), savedNames)
    }

    /** FR-SCALE-013 : adresse, nom annoncé et pilote, groupés et donnés comme du diagnostic. */
    @Test
    fun theTechnicalBlockShowsTheThreeValuesAndOffersNothingToChange() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.DIAGNOSTICS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SCALE.address).assertIsDisplayed()
        compose.onNodeWithText(SCALE.advertisedName).assertIsDisplayed()
        compose.onNodeWithText(SCALE.driverId).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.DIAGNOSTICS_TITLE).assertIsDisplayed()
    }

    @Test
    fun theModelAndTheLastContactAreShown() {
        show(SCALE)

        compose.onNodeWithText(SCALE.modelName).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.NOT_IN_RANGE).assertIsDisplayed()
    }

    @Test
    fun aScaleThatWasNeverReachedSaysSo() {
        show(SCALE.copy(lastSeenAt = null))

        compose.onNodeWithText(ScaleMessages.NEVER_CONNECTED).assertIsDisplayed()
    }

    /** FR-SCALE-014 : oublier demande une confirmation. */
    @Test
    fun forgettingAsksFirst() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.FORGET).performScrollTo().performClick()

        assertEquals(1, forgetRequested)
        assertEquals(0, forgetConfirmed)
    }

    /**
     * BR-SCALE-010 dans la confirmation elle-même : c'est cette phrase qui rend la question
     * acceptable sans réfléchir, et elle doit donc être à l'écran.
     */
    @Test
    fun theConfirmationPromisesEveryMeasurementIsKept() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithText(ScaleMessages.FORGET_CONFIRMATION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.FORGET_CONFIRMATION_BODY).assertIsDisplayed()
    }

    @Test
    fun theSafeAnswerKeepsTheScale() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithTag(ScaleTestTags.KEEP_SCALE).assertHasClickAction().performClick()

        assertEquals(1, forgetCancelled)
        assertEquals(0, forgetConfirmed)
    }

    @Test
    fun confirmingForgetsIt() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithTag(ScaleTestTags.CONFIRM_FORGET).performClick()

        assertEquals(1, forgetConfirmed)
    }

    /** L'écran se referme sur une balance qui n'existe plus ; il ne doit rien dessiner d'elle. */
    @Test
    fun aForgottenScaleLeavesNothingBehind() {
        show(scale = null)

        compose.onNodeWithText(ScaleMessages.FORGET_THIS_SCALE).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.DIAGNOSTICS).assertDoesNotExist()
    }

    /** L'état est hissé ici : le brouillon du nom vit dans le test, jamais dans l'écran. */
    private fun show(scale: PairedScale?, forgetTarget: PairedScale? = null) {
        compose.setContent {
            var nameInput by remember { mutableStateOf(scale?.displayName.orEmpty()) }
            MueTheme {
                ScaleDetailContent(
                    scale = scale,
                    nameInput = nameInput,
                    forgetTarget = forgetTarget,
                    onNameChange = { nameInput = it },
                    onSaveName = { savedNames += it },
                    onForgetRequested = { forgetRequested++ },
                    onForgetCancelled = { forgetCancelled++ },
                    onForgetConfirmed = { forgetConfirmed++ },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }
}
