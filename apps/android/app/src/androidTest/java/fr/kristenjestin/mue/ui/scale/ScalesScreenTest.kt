package fr.kristenjestin.mue.ui.scale

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

private val SEEN: Instant = Instant.parse("2026-08-25T07:12:00Z")

private val BATHROOM = PairedScale(
    id = "a",
    displayName = "Bathroom scale",
    modelName = "Homebuds HB9027",
    driverId = "homebuds-hb9027",
    address = "FF:10:00:1F:52:C3",
    advertisedName = "Health Scale",
    lastSeenAt = SEEN,
    inRange = true,
)

private val DOWNSTAIRS = BATHROOM.copy(
    id = "b",
    displayName = "Downstairs",
    address = "FF:10:00:1F:52:C4",
    lastSeenAt = null,
    inRange = false,
)

/**
 * `Profile > Scales` : l'état vide, la liste, et le seul chemin vers l'appairage
 * (FR-SCALE-010, 013, PRD_SCALE 18.1).
 *
 * L'état est hissé dans le test et l'écran sans état est le seul pilote : aucune base, aucun
 * Bluetooth, aucune permission — ce qui est exactement ce que ces assertions ont à prouver.
 */
@RunWith(AndroidJUnit4::class)
class ScalesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var addRequested = 0
    private var backs = 0

    /** PRD_SCALE 18.1 : une invitation qui dit ce qu'une balance apporte, pas un manque. */
    @Test
    fun theEmptyStateExplainsWhatAScaleBringsAndOffersToAddOne() {
        show(ScalesUiState(loading = false))

        compose.onNodeWithTag(ScaleTestTags.EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SCALES_EMPTY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SCALES_EMPTY_BODY).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.ADD_SCALE).assertHasClickAction()
    }

    /** Rien ne clignote avant la première lecture : l'invitation viserait le mauvais lecteur. */
    @Test
    fun nothingIsOfferedBeforeTheFirstRead() {
        show(ScalesUiState(loading = true))

        compose.onNodeWithTag(ScaleTestTags.EMPTY_STATE).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.LIST).assertDoesNotExist()
    }

    @Test
    fun eachScaleShowsItsNameItsModelAndItsLastContact() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM, DOWNSTAIRS)))

        compose.onNodeWithTag(ScaleTestTags.LIST).assertIsDisplayed()
        compose.onNodeWithText("Bathroom scale").assertIsDisplayed()
        compose.onNodeWithText("Downstairs").assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.rowStatus("a")).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.rowStatus("b")).assertIsDisplayed()
    }

    /** FR-SCALE-013 : hors de portée est un état normal, énoncé en toutes lettres. */
    @Test
    fun aScaleThatWasNeverReachedSaysSoRatherThanShowingADate() {
        show(ScalesUiState(loading = false, scales = listOf(DOWNSTAIRS)))

        compose.onNodeWithTag(ScaleTestTags.rowStatus("b"))
            .assertTextContains(ScaleMessages.NEVER_CONNECTED, substring = true)
        compose.onNodeWithTag(ScaleTestTags.rowStatus("b"))
            .assertTextContains(ScaleMessages.NOT_IN_RANGE, substring = true)
    }

    @Test
    fun aScaleInRangeSaysSo() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)))

        compose.onNodeWithTag(ScaleTestTags.rowStatus("a"))
            .assertTextContains(ScaleMessages.IN_RANGE, substring = true)
    }

    @Test
    fun openingAScaleReportsIt() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM, DOWNSTAIRS)))

        compose.onNodeWithTag(ScaleTestTags.row("b")).performScrollTo().performClick()

        assertEquals(listOf("b"), opened)
    }

    /** FR-SCALE-010 : `Add a scale` est le seul chemin vers le flux d'appairage. */
    @Test
    fun addingAScaleIsOfferedWithScalesAlreadyPaired() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)))

        compose.onNodeWithTag(ScaleTestTags.ADD_SCALE).performScrollTo().performClick()

        assertEquals(1, addRequested)
    }

    /** PRD_SCALE 20 : la liste est utilisable sans le moindre geste de glissement. */
    @Test
    fun theWayBackIsAControlAndNotAGesture() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    private fun show(state: ScalesUiState) {
        compose.setContent {
            MueTheme {
                ScalesContent(
                    state = state,
                    onBack = { backs++ },
                    onAddScale = { addRequested++ },
                    onOpenScale = { opened += it },
                )
            }
        }
        compose.waitForIdle()
    }
}
