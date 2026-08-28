package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The scan panel as it reaches the glass (FR-FOOD-003, PRD_FOOD 17 and 18).
 *
 * Every state below is handed in whole, so none of these tests needs a camera, a permission or a
 * network — which is the point rather than a convenience. PRD_FOOD 18 makes the typed barcode an
 * equal path, and a test that could only run with a lens pointed at something would be unable to
 * check the very case the section exists for.
 *
 * The one thing a device *is* needed for lives next door in `ui/food/scan`: that ML Kit turns an
 * image into digits.
 */
class FoodAddScanScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val barcode = FoodAddPreviewData.SCANNED_BARCODE

    private fun show(
        state: FoodAddUiState,
        actions: FoodAddActions = FoodAddActions(),
    ) {
        compose.setContent {
            MueTheme { FoodAddScreen(state = state, actions = actions) }
        }
    }

    /** A scan state as the screen would receive it, with the camera answered by the caller. */
    private fun scanState(
        typed: String = "",
        scan: FoodScanState = FoodScanState.Idle,
        granted: Boolean = false,
        available: Boolean = true,
        canRequest: Boolean = false,
    ): FoodAddUiState {
        val built = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true, scanBarcode = typed),
            today = FoodAddPreviewData.TODAY,
            scan = scan,
        )
        return built.copy(
            scan = built.scan?.withCamera(
                isGranted = granted,
                isAvailable = available,
                canRequest = canRequest,
            ),
        )
    }

    // --- PRD_FOOD 7: the fourth way in ----------------------------------------------------------

    @Test
    fun theWaysInOfferTheBarcode() {
        var scanned = false
        show(previewPathsState(), FoodAddActions(onScan = { scanned = true }))

        compose.onNodeWithTag(FoodTestTags.ADD_BY_SCAN).performScrollTo().performClick()

        assertEquals(true, scanned)
    }

    /** PRD_FOOD 18: the card names both ways in, so neither reads as the other's repair. */
    @Test
    fun theBarcodeCardNamesTheTypedNumberBesideTheCamera() {
        show(previewPathsState())

        // The card clears its children's semantics into one announcement, so the sentence is
        // read off the content description rather than looked for as a `Text` node.
        compose.onNodeWithTag(FoodTestTags.ADD_BY_SCAN)
            .performScrollTo()
            .assertContentDescriptionContains(
                "${FoodAddMessages.SCAN_PATH}. ${FoodAddMessages.SCAN_PATH_DESCRIPTION}",
            )
    }

    // --- PRD_FOOD 18: a refusal is a path, not a dead end ---------------------------------------

    /**
     * The rule the whole section turns on. With the camera refused there is no preview — and the
     * field, its label and its button are all still there, in the same place, at the same size.
     */
    @Test
    fun aRefusedCameraLandsOnTheFieldWithAnExplanation() {
        show(scanState(granted = false, canRequest = false))

        compose.onNodeWithTag(FoodTestTags.SCANNER_PREVIEW).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.BARCODE_FIELD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.CAMERA_REFUSED).assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.LOOK_UP).performScrollTo().assertIsDisplayed()
    }

    /** Android will not show the prompt twice, so the control after a refusal is not a prompt. */
    @Test
    fun aRefusedCameraIsNeverOfferedASecondPrompt() {
        show(scanState(granted = false, canRequest = false))

        compose.onNodeWithText(FoodAddMessages.ALLOW_CAMERA).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.OPEN_CAMERA_SETTINGS)
            .performScrollTo()
            .assertHasClickAction()
    }

    @Test
    fun aCameraNeverAskedForIsOfferedOnce() {
        var asked = 0
        show(
            scanState(granted = false, canRequest = true),
            FoodAddActions(onCameraAction = { asked++ }),
        )

        compose.onNodeWithText(FoodAddMessages.ALLOW_CAMERA).performScrollTo().performClick()

        assertEquals(1, asked)
    }

    /** A device with no camera is told that, and never told to grant something. */
    @Test
    fun aDeviceWithNoCameraIsNotToldToGrantAnything() {
        show(scanState(available = false))

        compose.onNodeWithText(FoodAddMessages.CAMERA_ABSENT).assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.ALLOW_CAMERA).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.OPEN_CAMERA_SETTINGS).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.BARCODE_FIELD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theFieldAcceptsATypedNumber() {
        val typed = StringBuilder()
        show(
            scanState(granted = false),
            FoodAddActions(onBarcodeChange = { typed.append(it) }),
        )

        // `MueTextField` tags its container, so the editable node is reached through `field`,
        // the helper every other form test in this suite uses.
        compose.onNodeWithTag(FoodTestTags.BARCODE_FIELD).performScrollTo()
        compose.field(FoodTestTags.BARCODE_FIELD).performTextInput("3")

        assertEquals("3", typed.toString())
    }

    // --- PRD_FOOD 13.1 and 17: what a found product shows ----------------------------------------

    /**
     * The product on screen **before** anything is written, with its gap visible.
     *
     * The fibre row reads `—` because Open Food Facts only has an estimate for it. That dash is
     * the whole discipline of the module arriving at the glass, and it is asserted here rather
     * than only in a unit test because this is the last place it could become a `0`.
     */
    @Test
    fun aFoundProductShowsAnUndocumentedValueAsADash() {
        show(
            scanState(
                typed = barcode,
                scan = FoodScanState.Found(
                    FoodAddPreviewData.scannedProduct(),
                    alreadyInCatalogue = false,
                ),
                granted = false,
            ),
        )

        compose.onNodeWithTag(FoodTestTags.nutrientField(FoodNutrientsUiState.FIBRE))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithText(FoodAddMessages.INCOMPLETE_CARD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFoundProductIsAddedByAnActionThatSaysWhatItDoes() {
        var added = false
        show(
            scanState(
                typed = barcode,
                scan = FoodScanState.Found(
                    FoodAddPreviewData.scannedProduct(),
                    alreadyInCatalogue = false,
                ),
            ),
            FoodAddActions(onUseScannedProduct = { added = true }),
        )

        compose.onNodeWithText(FoodAddMessages.ADD_THIS_PRODUCT).performScrollTo().performClick()

        assertEquals(true, added)
    }

    /** Neither stage has a line to write, so no `Save entry` is raised over the panel. */
    @Test
    fun theScanRaisesNoSaveButton() {
        show(scanState(typed = barcode))

        compose.onNodeWithText(FoodAddMessages.SAVE_ENTRY).assertDoesNotExist()
    }

    // --- PRD_FOOD 17: the two ways there is no product -------------------------------------------

    @Test
    fun aMissingProductOffersTheCreationAndNoRetry() {
        var created = false
        show(
            scanState(typed = barcode, scan = FoodScanState.NotFound(barcode)),
            FoodAddActions(onCreateFromBarcode = { created = true }),
        )

        compose.onNodeWithText(FoodAddMessages.TRY_LOOKUP_AGAIN).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.CREATE_FROM_BARCODE).performScrollTo().performClick()

        assertEquals(true, created)
    }

    /** PRD_FOOD 17: "message explicite" — the sentence names the cause, and the code survives. */
    @Test
    fun anOfflineLookupSaysSoAndKeepsEveryOtherWayOut() {
        show(
            scanState(
                typed = barcode,
                scan = FoodScanState.Unavailable(barcode, LookupFailure.OFFLINE),
            ),
        )

        compose.onNodeWithText(FoodAddMessages.lookupFailure(LookupFailure.OFFLINE))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.TRY_LOOKUP_AGAIN).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.CREATE_FROM_BARCODE).performScrollTo().assertIsDisplayed()
        // The number is still in the field, so nothing has to be read off the packet twice.
        compose.onNodeWithTag(FoodTestTags.BARCODE_FIELD).performScrollTo().assertIsDisplayed()
    }

    /**
     * Four causes reach the glass as four different sentences, never as one shrug.
     *
     * Driven through one `mutableStateOf` rather than four `setContent` calls, which the rule
     * allows exactly once — and which is also closer to what happens: the same panel, recomposed
     * as a retry produces a different answer.
     */
    @Test
    fun eachFailureReachesTheGlassUnderItsOwnName() {
        val reason = mutableStateOf(LookupFailure.OFFLINE)
        compose.setContent {
            MueTheme {
                FoodAddScreen(
                    state = scanState(
                        typed = barcode,
                        scan = FoodScanState.Unavailable(barcode, reason.value),
                    ),
                    actions = FoodAddActions(),
                )
            }
        }

        LookupFailure.entries.forEach { failure ->
            reason.value = failure
            compose.waitForIdle()
            compose.onNodeWithText(FoodAddMessages.lookupFailure(failure))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }
}
