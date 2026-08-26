package fr.kristenjestin.mue.ui.scale

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    private var permissionRequested = 0
    private var settingsOpened = 0
    private var bluetoothEnabled = 0
    private var locationSettingsOpened = 0

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

    // region les conditions d'Android sur `Scales` (FR-SCALE-025, PRD_SCALE 18.5)

    /**
     * PRD_SCALE 18.5, mot pour mot : « Bluetooth désactivé : `Scales` propose de l'activer ».
     *
     * Et il le propose **sans effacer la liste** : une radio éteinte n'est pas une perte de
     * données, les balances enregistrées restent lisibles avec leur nom, leur modèle et leur
     * dernier contact (FR-SCALE-013).
     */
    @Test
    fun aRadioThatIsOffIsOfferedToBeSwitchedOnWithoutHidingTheList() {
        show(
            ScalesUiState(loading = false, scales = listOf(BATHROOM, DOWNSTAIRS)),
            gate = ScanGate.BLUETOOTH_OFF,
        )

        compose.onNodeWithTag(ScaleTestTags.PERMISSION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.BLUETOOTH_OFF_EXPLANATION).assertIsDisplayed()

        compose.onNodeWithTag(ScaleTestTags.LIST).assertIsDisplayed()
        compose.onNodeWithText("Bathroom scale").assertIsDisplayed()
        compose.onNodeWithText("Downstairs").assertIsDisplayed()
        // Les deux gardent le modèle reconnu : la carte n'a rien retiré de la liste.
        compose.onAllNodesWithText(BATHROOM.modelName).assertCountEquals(2)
        compose.onNodeWithTag(ScaleTestTags.rowStatus("a"))
            .assertTextContains(ScaleMessages.LAST_SEEN_LABEL, substring = true)

        compose.onNodeWithTag(ScaleTestTags.ENABLE_BLUETOOTH).performScrollTo().performClick()

        assertEquals(1, bluetoothEnabled)
    }

    /**
     * PRD_SCALE 18.5 : sans scan, personne n'a constaté quoi que ce soit.
     *
     * `Not in range` serait un mensonge — la balance est peut-être là, allumée, sous les yeux du
     * lecteur — et surtout il désigne le mauvais geste : monter sur la balance plutôt que rallumer
     * la radio. La ligne s'arrête donc au dernier contact, qui reste vrai.
     */
    @Test
    fun withoutTheRadioNothingClaimsAScaleIsOutOfRange() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)), gate = ScanGate.BLUETOOTH_OFF)

        compose.onNodeWithTag(ScaleTestTags.rowStatus("a"))
            .assertTextContains(ScaleMessages.LAST_SEEN_LABEL, substring = true)
        compose.onNodeWithText(ScaleMessages.NOT_IN_RANGE, substring = true).assertDoesNotExist()
        compose.onNodeWithText(ScaleMessages.IN_RANGE, substring = true).assertDoesNotExist()
    }

    /**
     * FR-SCALE-025 : « permission refusée ou révoquée : `Scales` explique la permission
     * manquante », et **rien ne se demande** tant que le bouton n'est pas activé — aucun écran
     * système ne s'ouvre sans geste de l'utilisateur.
     */
    @Test
    fun aRevokedPermissionIsExplainedAndAsksForNothingOnItsOwn() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)), gate = ScanGate.PERMISSION_NEEDED)

        compose.onNodeWithTag(ScaleTestTags.PERMISSION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.PERMISSION_EXPLANATION).assertIsDisplayed()
        assertEquals(0, permissionRequested)

        compose.onNodeWithTag(ScaleTestTags.ALLOW_PERMISSION).performScrollTo().performClick()

        assertEquals(1, permissionRequested)
    }

    /** FR-SCALE-025 : un refus définitif renvoie aux réglages, et n'est jamais redemandé. */
    @Test
    fun aFinalRefusalLeadsToTheSettingsAndIsNeverAskedAgain() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)), gate = ScanGate.PERMISSION_DENIED)

        compose.onNodeWithText(ScaleMessages.PERMISSION_DENIED_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.ALLOW_PERMISSION).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.OPEN_SETTINGS).performScrollTo().performClick()

        assertEquals(1, settingsOpened)
        assertEquals(0, permissionRequested)
    }

    /** PRD_SCALE 16.1, API ≤ 30 : expliquée comme une exigence du système, avec son réglage. */
    @Test
    fun systemLocationIsExplainedOnTheListToo() {
        show(
            ScalesUiState(loading = false, scales = listOf(BATHROOM)),
            gate = ScanGate.SYSTEM_LOCATION_OFF,
        )

        compose.onNodeWithTag(ScaleTestTags.LOCATION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SYSTEM_LOCATION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.OPEN_LOCATION_SETTINGS).performScrollTo().performClick()

        assertEquals(1, locationSettingsOpened)
    }

    /**
     * PRD_SCALE 18.1 : sans balance, l'écran reste une invitation.
     *
     * Les trois phrases de PRD_SCALE 18.5 parlent de « votre balance » et n'ont personne à qui
     * s'adresser ici. Elles attendent le flux d'appairage, où FR-SCALE-025 met la demande.
     */
    @Test
    fun theEmptyStateStaysAnInvitationEvenWithTheRadioOff() {
        show(ScalesUiState(loading = false), gate = ScanGate.BLUETOOTH_OFF)

        compose.onNodeWithTag(ScaleTestTags.EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.PERMISSION_EXPLANATION).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.ADD_SCALE).assertHasClickAction()
    }

    /** Rien n'est proposé avant la première lecture, pas même une condition d'Android. */
    @Test
    fun nothingIsExplainedBeforeTheFirstRead() {
        show(ScalesUiState(loading = true), gate = ScanGate.BLUETOOTH_OFF)

        compose.onNodeWithTag(ScaleTestTags.PERMISSION_EXPLANATION).assertDoesNotExist()
    }

    // endregion

    /** PRD_SCALE 20 : la liste est utilisable sans le moindre geste de glissement. */
    @Test
    fun theWayBackIsAControlAndNotAGesture() {
        show(ScalesUiState(loading = false, scales = listOf(BATHROOM)))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    private fun show(state: ScalesUiState, gate: ScanGate = ScanGate.READY) {
        compose.setContent {
            MueTheme {
                ScalesContent(
                    state = state,
                    gate = gate,
                    onBack = { backs++ },
                    onAddScale = { addRequested++ },
                    onOpenScale = { opened += it },
                    onRequestPermission = { permissionRequested++ },
                    onOpenSettings = { settingsOpened++ },
                    onEnableBluetooth = { bluetoothEnabled++ },
                    onOpenLocationSettings = { locationSettingsOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }
}
