package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

private val KNOWN = DiscoveredScale(
    address = "FF:10:00:1F:52:C3",
    advertisedName = "Health Scale",
    driverId = "homebuds-hb9027",
    modelName = "Homebuds HB9027",
)

private val SPEAKER = UnsupportedDevice("AA:BB:CC:DD:EE:01", "Living room speaker")

/**
 * Le flux d'appairage (FR-SCALE-011, 012), le rattachement (FR-SCALE-001) et les quatre
 * conditions d'Android (FR-SCALE-025, PRD_SCALE 18.5).
 *
 * Tout est hissé : ces tests ne touchent ni au Bluetooth, ni aux permissions, ce qui est la seule
 * façon de couvrir les quatre états de permission sur une machine qui n'en a qu'un.
 */
@RunWith(AndroidJUnit4::class)
class ScaleScanScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<String>()
    private var scansRequested = 0
    private var permissionRequested = 0
    private var settingsOpened = 0
    private var bluetoothEnabled = 0
    private var locationSettingsOpened = 0
    private var reattached = 0
    private var declined = 0

    /** FR-SCALE-011 : la phrase la plus utile de l'écran. */
    @Test
    fun theScanSaysASleepingScaleIsInvisible() {
        show(ScaleScanUiState(scanning = true, started = true))

        compose.onNodeWithTag(ScaleTestTags.SCAN_HINT).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SCAN_WAKE_HINT).assertIsDisplayed()
    }

    @Test
    fun recognisedDevicesComeFirstWithTheModelThatWasIdentified() {
        show(ScaleScanUiState(scanning = true, started = true, recognised = listOf(KNOWN)))

        compose.onNodeWithTag(ScaleTestTags.RECOGNISED_DEVICES).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.RECOGNISED_HEADING).assertIsDisplayed()
        compose.onNodeWithText(KNOWN.modelName).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.device(KNOWN.address)).assertHasClickAction()
    }

    /**
     * FR-SCALE-011 : listés, grisés, non sélectionnables. C'est ce qui permet de constater que Mue
     * voit l'appareil sans savoir lui parler, au lieu de conclure à une panne Bluetooth.
     */
    @Test
    fun unsupportedDevicesAreListedAndCannotBeChosen() {
        show(ScaleScanUiState(scanning = true, started = true, unsupported = listOf(SPEAKER)))

        compose.onNodeWithTag(ScaleTestTags.UNSUPPORTED_DEVICES).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.UNSUPPORTED_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.UNSUPPORTED_BADGE).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.device(SPEAKER.address)).assertHasNoClickAction()
    }

    @Test
    fun choosingARecognisedDeviceReportsIt() {
        show(ScaleScanUiState(scanning = true, started = true, recognised = listOf(KNOWN)))

        compose.onNodeWithTag(ScaleTestTags.device(KNOWN.address)).performScrollTo().performClick()

        assertEquals(listOf(KNOWN.address), selected)
    }

    /** Une balance déjà appairée sous cette adresse reste visible, sans rien à faire d'elle. */
    @Test
    fun anAlreadyPairedDeviceIsShownButNotSelectable() {
        show(
            ScaleScanUiState(
                scanning = true,
                started = true,
                recognised = listOf(KNOWN.copy(alreadyPairedAs = "Bathroom scale")),
            ),
        )

        compose.onNodeWithTag(ScaleTestTags.device(KNOWN.address)).assertHasNoClickAction()
    }

    /** FR-SCALE-011 : le scan s'arrête au bout de trente secondes et propose de recommencer. */
    @Test
    fun theOfferToScanAgainOnlyExistsOnceTheScanHasStopped() {
        show(ScaleScanUiState(scanning = true, started = true))
        compose.onNodeWithTag(ScaleTestTags.SCAN_AGAIN).assertDoesNotExist()

        show(ScaleScanUiState(scanning = false, started = true))
        compose.onNodeWithText(ScaleMessages.SCAN_FINISHED).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.SCAN_AGAIN).performScrollTo().performClick()

        assertEquals(1, scansRequested)
    }

    /** PRD_SCALE 7.3 : le silence n'est pas une erreur, mais il se nomme. */
    @Test
    fun findingNothingIsSaidWithoutBeingAnError() {
        show(ScaleScanUiState(scanning = false, started = true))

        compose.onNodeWithText(ScaleMessages.SCAN_FOUND_NOTHING).assertIsDisplayed()
    }

    // region les quatre conditions d'Android (FR-SCALE-025, PRD_SCALE 18.5)

    @Test
    fun theFirstPairingIsWhereTheBluetoothPermissionIsAskedFor() {
        show(ScaleScanUiState(gate = ScanGate.PERMISSION_NEEDED))

        compose.onNodeWithTag(ScaleTestTags.PERMISSION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.PERMISSION_EXPLANATION).assertIsDisplayed()
        // Le scan n'est même pas dessiné tant que la condition n'est pas levée.
        compose.onNodeWithTag(ScaleTestTags.SCAN_HINT).assertDoesNotExist()
    }

    @Test
    fun aFinalRefusalLeadsToSettingsAndNowhereElse() {
        show(ScaleScanUiState(gate = ScanGate.PERMISSION_DENIED))

        compose.onNodeWithText(ScaleMessages.PERMISSION_DENIED_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.OPEN_SETTINGS).performClick()

        assertEquals(1, settingsOpened)
        assertEquals(0, permissionRequested)
    }

    @Test
    fun aRadioThatIsOffIsOfferedToBeSwitchedOn() {
        show(ScaleScanUiState(gate = ScanGate.BLUETOOTH_OFF))

        compose.onNodeWithText(ScaleMessages.BLUETOOTH_OFF_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.ENABLE_BLUETOOTH).performClick()

        assertEquals(1, bluetoothEnabled)
    }

    /** PRD_SCALE 16.1, API ≤ 30 : expliqué, plutôt que subi comme une liste vide. */
    @Test
    fun systemLocationIsExplainedRatherThanSuffered() {
        show(ScaleScanUiState(gate = ScanGate.SYSTEM_LOCATION_OFF))

        compose.onNodeWithTag(ScaleTestTags.LOCATION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SYSTEM_LOCATION_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.OPEN_LOCATION_SETTINGS).performClick()

        assertEquals(1, locationSettingsOpened)
    }

    // endregion

    // region le rattachement d'adresse (FR-SCALE-001)

    /** Proposé, jamais silencieux — et les deux réponses sont constructives. */
    @Test
    fun reattachingIsAQuestionWithTwoUsableAnswers() {
        val device = KNOWN.copy(reattachTo = ReattachCandidate("a", "Bathroom scale"))
        show(
            ScaleScanUiState(
                started = true,
                recognised = listOf(device),
                proposal = ReattachProposal(device, ReattachCandidate("a", "Bathroom scale")),
            ),
        )

        // Le nom donné à la balance enregistrée est ce qui rend la question répondable.
        compose.onNodeWithText("Bathroom scale", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Reattach").assertHasClickAction().performClick()
        assertEquals(1, reattached)

        compose.onNodeWithText("Add as a new scale").assertHasClickAction().performClick()
        assertEquals(1, declined)
    }

    // endregion

    /**
     * L'état est hissé dans un `mutableStateOf` du test : `setContent` ne s'appelle qu'une fois par
     * test, et deux des cas ci-dessus ont besoin de voir l'écran changer d'état.
     */
    private val hoisted = mutableStateOf(ScaleScanUiState())

    private var composed = false

    private fun show(state: ScaleScanUiState) {
        hoisted.value = state
        if (composed) {
            compose.waitForIdle()
            return
        }
        composed = true
        compose.setContent {
            MueTheme {
                ScaleScanContent(
                    state = hoisted.value,
                    onScanAgain = { scansRequested++ },
                    onDeviceSelected = { selected += it.address },
                    onRequestPermission = { permissionRequested++ },
                    onOpenSettings = { settingsOpened++ },
                    onEnableBluetooth = { bluetoothEnabled++ },
                    onOpenLocationSettings = { locationSettingsOpened++ },
                    onReattachConfirmed = { reattached++ },
                    onReattachDeclined = { declined++ },
                    onProposalDismissed = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }
}
