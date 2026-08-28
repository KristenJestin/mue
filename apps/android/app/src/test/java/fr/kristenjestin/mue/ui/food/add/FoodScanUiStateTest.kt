package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.repository.LookupFailure
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan panel as a pure function (FR-FOOD-003, PRD_FOOD 9.2, 13.1, 17 and 18).
 *
 * Everything asserted here is decided before a camera, a socket or a frame is involved, which is
 * what makes it assertable at all: the emulator's camera is a virtual scene and cannot be made to
 * hold a jar, so the rules that matter are pushed into a function that needs neither.
 */
class FoodScanUiStateTest {

    private val barcode = FoodAddPreviewData.SCANNED_BARCODE

    private fun state(
        typed: String = "",
        scan: FoodScanState = FoodScanState.Idle,
        attempted: Boolean = false,
        saveError: String? = null,
    ): FoodScanUiState {
        val built = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true, scanBarcode = typed),
            today = FoodAddPreviewData.TODAY,
            scan = scan,
            scanAttempted = attempted,
            scanSaveError = saveError,
        )
        assertEquals(FoodAddStage.SCAN, built.stage)
        return assertNotNull(built.scan)
    }

    // --- the stage itself ----------------------------------------------------------------------

    @Test
    fun `taking the scan path puts the sheet on the scan stage`() {
        assertEquals(FoodAddStage.SCAN, FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true),
            today = FoodAddPreviewData.TODAY,
        ).stage)
    }

    /** Neither stage has a line to write, so neither raises a button that could only refuse. */
    @Test
    fun `the scan raises no save action`() {
        val built = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true),
            today = FoodAddPreviewData.TODAY,
        )

        assertFalse(built.showsSaveAction)
    }

    @Test
    fun `the scan can be left for the other ways in`() {
        val built = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true),
            today = FoodAddPreviewData.TODAY,
        )

        assertTrue(built.canReturnToPaths)
    }

    /** A food that has arrived outranks the scan, so accepting a product moves the sheet on. */
    @Test
    fun `a chosen food takes the sheet off the scan stage`() {
        val product = FoodAddPreviewData.scannedProduct()
        val built = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(scanning = true, foodId = product.id.value),
            food = product,
            today = FoodAddPreviewData.TODAY,
        )

        assertEquals(FoodAddStage.AMOUNT, built.stage)
        assertNull(built.scan)
    }

    // --- the field (PRD_FOOD 15 and 18) ---------------------------------------------------------

    @Test
    fun `a barcode being typed is not scolded before it has been submitted`() {
        assertNull(state(typed = "301").barcodeError)
    }

    @Test
    fun `a submitted barcode that is not one is refused in the domain's own words`() {
        val refused = state(typed = "301", attempted = true)

        assertNotNull(refused.barcodeError)
    }

    @Test
    fun `a valid barcode carries no refusal even after an attempt`() {
        assertNull(state(typed = barcode, attempted = true).barcodeError)
    }

    @Test
    fun `the lookup is offered on anything typed, so pressing it is what explains a bad number`() {
        assertFalse(state(typed = "").canLookUp)
        assertTrue(state(typed = "3").canLookUp)
        assertTrue(state(typed = barcode).canLookUp)
    }

    @Test
    fun `nothing can be looked up twice at once`() {
        assertFalse(state(typed = barcode, scan = FoodScanState.LookingUp).canLookUp)
        assertTrue(state(typed = barcode, scan = FoodScanState.LookingUp).isLookingUp)
    }

    // --- the camera, and the three sentences PRD_FOOD 17 asks for -------------------------------

    @Test
    fun `a granted camera is live and says nothing`() {
        val scan = state().withCamera(isGranted = true, isAvailable = true, canRequest = false)

        assertTrue(scan.isCameraLive)
        assertNull(scan.cameraExplanation)
        assertNull(scan.cameraActionLabel)
    }

    @Test
    fun `a camera never asked for is offered once, with the field named as an equal`() {
        val scan = state().withCamera(isGranted = false, isAvailable = true, canRequest = true)

        assertFalse(scan.isCameraLive)
        assertEquals(FoodAddMessages.CAMERA_NOT_YET_ALLOWED, scan.cameraExplanation)
        assertEquals(FoodAddMessages.ALLOW_CAMERA, scan.cameraActionLabel)
    }

    /**
     * The rule the brief calls non-negotiable: a refusal is a path, and **the app never asks
     * twice**. The label is what proves the second half — it names Android settings, which is the
     * only place the answer can still change, rather than a prompt Android would not show.
     */
    @Test
    fun `a refused camera explains itself and never offers a second prompt`() {
        val scan = state().withCamera(isGranted = false, isAvailable = true, canRequest = false)

        assertFalse(scan.isCameraLive)
        assertEquals(FoodAddMessages.CAMERA_REFUSED, scan.cameraExplanation)
        assertEquals(FoodAddMessages.OPEN_CAMERA_SETTINGS, scan.cameraActionLabel)
        assertFalse(
            scan.cameraActionLabel == FoodAddMessages.ALLOW_CAMERA,
            "Android does not show the prompt again; a control that said it would is a lie",
        )
    }

    /** No camera on the device: nothing to grant, so nothing about permissions is said. */
    @Test
    fun `a device with no camera is told that, and not told to grant anything`() {
        val scan = state().withCamera(isGranted = false, isAvailable = false, canRequest = false)

        assertFalse(scan.isCameraLive)
        assertEquals(FoodAddMessages.CAMERA_ABSENT, scan.cameraExplanation)
        assertNull(scan.cameraActionLabel)
    }

    /** PRD_FOOD 18: the field is an equal alternative, so nothing about it depends on the camera. */
    @Test
    fun `the field and its button are identical with the camera on and with it refused`() {
        val typed = state(typed = barcode)
        val live = typed.withCamera(isGranted = true, isAvailable = true, canRequest = false)
        val refused = typed.withCamera(isGranted = false, isAvailable = true, canRequest = false)

        assertEquals(live.barcode, refused.barcode)
        assertEquals(live.canLookUp, refused.canLookUp)
        assertEquals(live.barcodeError, refused.barcodeError)
    }

    // --- what a lookup produced (PRD_FOOD 9.2, 13.1 and 17) -------------------------------------

    /**
     * The rule that must survive every layer: an Open Food Facts card routinely documents no
     * fibre, and PRD_FOOD 13.2 draws an unknown as `—`. This is the last place it could be turned
     * into a zero before it reaches the glass.
     */
    @Test
    fun `an undocumented fibre is drawn as a dash and never as a zero`() {
        val found = state(
            scan = FoodScanState.Found(
                FoodAddPreviewData.scannedProduct(),
                alreadyInCatalogue = false,
            ),
        ).found

        val fibre = assertNotNull(found).per100.rows
            .single { it.key == FoodNutrientsUiState.FIBRE }

        assertEquals("—", fibre.value)
        assertFalse(fibre.value.contains('0'))
    }

    @Test
    fun `the four values the card does document are drawn as figures`() {
        val found = assertNotNull(
            state(
                scan = FoodScanState.Found(
                    FoodAddPreviewData.scannedProduct(),
                    alreadyInCatalogue = false,
                ),
            ).found,
        )

        val energy = found.per100.rows.single { it.key == FoodNutrientsUiState.ENERGY }
        assertTrue(energy.value.contains("539"), energy.value)
    }

    @Test
    fun `an incomplete card says so, so a dash does not read as a defect in Mue`() {
        val found = assertNotNull(
            state(
                scan = FoodScanState.Found(
                    FoodAddPreviewData.scannedProduct(),
                    alreadyInCatalogue = false,
                ),
            ).found,
        )

        assertEquals(FoodAddMessages.INCOMPLETE_CARD, found.incompleteNote)
    }

    @Test
    fun `a card documenting all five says nothing about being incomplete`() {
        val complete = FoodAddPreviewData.scannedProduct().let { product ->
            product.copy(
                per100 = product.per100.copy(
                    fibre = fr.kristenjestin.mue.domain.model.Macro.ofGramsOrNull(3.4),
                ),
            )
        }

        val found = assertNotNull(
            state(scan = FoodScanState.Found(complete, alreadyInCatalogue = false)).found,
        )

        assertNull(found.incompleteNote)
    }

    /**
     * A product already in the catalogue is *chosen*, not copied again — PRD_FOOD 9.2 keeps the
     * local copy authoritative once it exists, so the button must not promise to add anything.
     */
    @Test
    fun `a product already in the catalogue is used rather than added`() {
        val kept = state(
            scan = FoodScanState.Found(
                FoodAddPreviewData.scannedProduct(),
                alreadyInCatalogue = true,
            ),
        ).found

        assertEquals(FoodAddMessages.USE_THIS_FOOD, assertNotNull(kept).actionLabel)
    }

    @Test
    fun `a new product is offered as something to add`() {
        val fresh = state(
            scan = FoodScanState.Found(
                FoodAddPreviewData.scannedProduct(),
                alreadyInCatalogue = false,
            ),
        ).found

        assertEquals(FoodAddMessages.ADD_THIS_PRODUCT, assertNotNull(fresh).actionLabel)
    }

    @Test
    fun `a found product shows no notice`() {
        assertNull(
            state(
                scan = FoodScanState.Found(
                    FoodAddPreviewData.scannedProduct(),
                    alreadyInCatalogue = false,
                ),
            ).notice,
        )
    }

    // --- the two ways there is no product (PRD_FOOD 17) -----------------------------------------

    /**
     * A product Open Food Facts does not have will not be there next time either, so the panel
     * offers the creation and **not** a retry: an invitation to repeat a request whose answer is
     * already known is an invitation to waste somebody's afternoon.
     */
    @Test
    fun `a missing product offers the prefilled creation and no retry`() {
        val notice = assertNotNull(state(scan = FoodScanState.NotFound(barcode)).notice)

        assertEquals(FoodAddMessages.PRODUCT_NOT_FOUND, notice.message)
        assertTrue(notice.canCreate)
        assertFalse(notice.canRetry)
        assertTrue(assertNotNull(notice.detail).contains(barcode), notice.detail)
    }

    @Test
    fun `a failure offers both a retry and the creation`() {
        LookupFailure.entries.forEach { reason ->
            val notice = assertNotNull(
                state(scan = FoodScanState.Unavailable(barcode, reason)).notice,
            )

            assertTrue(notice.canRetry, reason.name)
            assertTrue(notice.canCreate, reason.name)
        }
    }

    /**
     * The brief's other non-negotiable: **a refusal names itself.** Four causes, four distinct
     * messages and four distinct details — no pair of them collapses into "something went wrong".
     */
    @Test
    fun `the four failures produce four different sentences`() {
        val messages = LookupFailure.entries.map { reason ->
            assertNotNull(state(scan = FoodScanState.Unavailable(barcode, reason)).notice).message
        }

        assertEquals(LookupFailure.entries.size, messages.toSet().size, messages.toString())
    }

    @Test
    fun `the four failures produce four different explanations of what to do`() {
        val details = LookupFailure.entries.map { reason ->
            assertNotNull(state(scan = FoodScanState.Unavailable(barcode, reason)).notice).detail
        }

        assertEquals(LookupFailure.entries.size, details.toSet().size, details.toString())
    }

    /** Being offline is about the network, and the message must not blame Open Food Facts. */
    @Test
    fun `offline names the connection and not the service`() {
        val notice = assertNotNull(
            state(scan = FoodScanState.Unavailable(barcode, LookupFailure.OFFLINE)).notice,
        )

        assertTrue(notice.message.contains("No connection"), notice.message)
        // PRD_FOOD 22: the reassurance is also the truth about what was sent.
        assertTrue(
            assertNotNull(notice.detail).contains("never left this phone"),
            notice.detail,
        )
    }

    @Test
    fun `nothing is on screen before a lookup has been made`() {
        val idle = state(typed = barcode)

        assertNull(idle.found)
        assertNull(idle.notice)
        assertFalse(idle.isLookingUp)
    }

    @Test
    fun `a failed copy into the catalogue says nothing was changed`() {
        val scan = state(
            scan = FoodScanState.Found(
                FoodAddPreviewData.scannedProduct(),
                alreadyInCatalogue = false,
            ),
            saveError = FoodAddMessages.COPY_FAILED,
        )

        assertEquals(FoodAddMessages.COPY_FAILED, scan.saveError)
    }

    /** A food with nothing known at all is still shown, with five dashes and the note. */
    @Test
    fun `a card documenting nothing is five dashes rather than five zeros`() {
        val bare = Food(
            id = FoodId("bare"),
            name = "Something",
            source = FoodSource.OPEN_FOOD_FACTS,
            per100 = Nutrients.UNKNOWN,
            barcode = barcode,
            sourceId = barcode,
        )

        val found = assertNotNull(
            state(scan = FoodScanState.Found(bare, alreadyInCatalogue = false)).found,
        )

        assertTrue(found.per100.rows.all { it.value == "—" }, found.per100.rows.toString())
    }
}
