package fr.kristenjestin.mue.ui.food.add

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import fr.kristenjestin.mue.ui.food.day.FakeFoodLogRepository
import fr.kristenjestin.mue.ui.food.day.FakeMealPlanRepository
import fr.kristenjestin.mue.ui.food.recipes.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-FOOD-003 driven end to end, with the camera, the socket and the emulator all absent.
 *
 * That absence is the design and not a compromise. PRD_FOOD 18 makes the typed barcode "une
 * alternative **complète** à la caméra", which means every rule past the digits — the local hit,
 * the copy into the catalogue, the four named failures, the prefilled creation — is reachable
 * without a lens. So all of it is proved here, on the JVM, and the only thing left for a device
 * is that ML Kit really turns an image into those digits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodAddScanViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    private val barcode = FoodAddPreviewData.SCANNED_BARCODE

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- taking the path ------------------------------------------------------------------------

    @Test
    fun `the scan path opens the scan stage with an empty field`() = scanTest { add ->
        add.viewModel.onScanChosen()

        val state = state(add)
        assertEquals(FoodAddStage.SCAN, state.stage)
        assertEquals("", assertNotNull(state.scan).barcode)
        assertTrue(add.lookup.requested.isEmpty(), "opening the scanner asks nothing of anyone")
    }

    /** PRD_FOOD 7: a path chosen is a path that can be unchosen, and the scan is no exception. */
    @Test
    fun `going back to the ways in leaves the scan and forgets the number`() = scanTest { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)

        add.viewModel.onBackToPaths()

        val state = state(add)
        assertEquals(FoodAddStage.PATHS, state.stage)
        assertNull(state.scan)
    }

    // --- the field, and what it will and will not send ------------------------------------------

    @Test
    fun `only digits reach the field`() = scanTest { add ->
        add.viewModel.onScanChosen()

        add.viewModel.onBarcodeChange(" 30-17 62/04a22003 ")

        assertEquals("3017620422003", assertNotNull(state(add).scan).barcode)
    }

    @Test
    fun `a number longer than any retail barcode is cut rather than sent`() = scanTest { add ->
        add.viewModel.onScanChosen()

        add.viewModel.onBarcodeChange("1".repeat(40))

        assertEquals(14, assertNotNull(state(add).scan).barcode.length)
    }

    /** PRD_FOOD 15's refusal, and **nothing on a socket**: a bad number is caught before the wire. */
    @Test
    fun `a half-typed barcode is refused without a request being made`() = scanTest { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange("301")

        add.viewModel.onLookUpBarcode()

        val scan = assertNotNull(state(add).scan)
        assertNotNull(scan.barcodeError)
        assertTrue(add.lookup.requested.isEmpty())
        assertNull(scan.notice)
    }

    @Test
    fun `editing the field takes back the refusal it earned`() = scanTest { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange("301")
        add.viewModel.onLookUpBarcode()
        assertNotNull(assertNotNull(state(add).scan).barcodeError)

        add.viewModel.onBarcodeChange(barcode)

        assertNull(assertNotNull(state(add).scan).barcodeError)
    }

    // --- the local catalogue comes first (PRD_FOOD 9.2) -----------------------------------------

    /**
     * "Le produit est copié dans le catalogue local … scanner à nouveau retrouve cette ligne."
     *
     * The local row wins, and the network is **not** touched — which is also what makes
     * re-scanning a kept product work in a cellar with no signal.
     */
    @Test
    fun `a barcode already in the catalogue is answered locally, with no request`() = scanTest(
        catalogue = listOf(FoodAddPreviewData.scannedProduct()),
    ) { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)

        add.viewModel.onLookUpBarcode()

        val found = assertNotNull(assertNotNull(state(add).scan).found)
        assertEquals(FoodAddMessages.USE_THIS_FOOD, found.actionLabel)
        assertTrue(add.lookup.requested.isEmpty(), "a kept product must not be fetched again")
    }

    /** The local row is the one the person may have corrected, not the one upstream holds. */
    @Test
    fun `the locally corrected values are the ones shown, not the remote card's`() = scanTest(
        catalogue = listOf(
            FoodAddPreviewData.scannedProduct().copy(name = "Nutella, my jar"),
        ),
    ) { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()

        assertEquals("Nutella, my jar", assertNotNull(assertNotNull(state(add).scan).found).name)
    }

    /**
     * The two names one product can have (PRD_FOOD 9.2).
     *
     * `MlKitBarcodeDecoderTest` measures the case on a device: a UPC-A reads back as **twelve**
     * digits while Open Food Facts files the card under thirteen, so a row copied from a scan
     * holds one in `barcode` and the other in `sourceId`. Looking up only the first would fetch,
     * and copy a second time, a product this catalogue already holds.
     */
    @Test
    fun `a code that matches only the source id still finds the copied row`() = scanTest(
        catalogue = listOf(
            FoodAddPreviewData.scannedProduct().copy(
                barcode = "000050184453",
                sourceId = "0000050184453",
            ),
        ),
    ) { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange("0000050184453")

        add.viewModel.onLookUpBarcode()

        val found = assertNotNull(assertNotNull(state(add).scan).found)
        assertEquals(FoodAddMessages.USE_THIS_FOOD, found.actionLabel)
        assertTrue(add.lookup.requested.isEmpty(), "the row exists under its other name")
    }

    @Test
    fun `choosing a product already in the catalogue writes nothing`() = scanTest(
        catalogue = listOf(FoodAddPreviewData.scannedProduct()),
    ) { add ->
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()
        advanceUntilIdle()

        add.viewModel.onUseScannedProduct()

        assertEquals(FoodAddStage.AMOUNT, state(add).stage)
        assertTrue(add.foods.saved.isEmpty(), "the row already existed; nothing was rewritten")
    }

    // --- a product found remotely ---------------------------------------------------------------

    @Test
    fun `a found product is shown before anything is written`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()))
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)

        add.viewModel.onLookUpBarcode()

        val found = assertNotNull(assertNotNull(state(add).scan).found)
        assertEquals(FoodAddMessages.ADD_THIS_PRODUCT, found.actionLabel)
        assertTrue(add.foods.saved.isEmpty(), "PRD_FOOD 9.2 copies at the moment of adding")
    }

    /** PRD_FOOD 9.2: the copy happens on the tap, carrying its provenance with it. */
    @Test
    fun `adding the product copies it into the catalogue and moves on to the quantity`() =
        scanTest { add ->
            val product = FoodAddPreviewData.scannedProduct()
            add.lookup.answer(barcode, ProductLookupResult.Found(product))
            add.viewModel.onScanChosen()
            add.viewModel.onBarcodeChange(barcode)
            add.viewModel.onLookUpBarcode()
            advanceUntilIdle()

            add.viewModel.onUseScannedProduct()
            advanceUntilIdle()

            val stored = add.foods.saved.single()
            assertEquals(FoodSource.OPEN_FOOD_FACTS, stored.source)
            assertEquals(barcode, stored.barcode)
            assertEquals(product.sourceId, stored.sourceId)
            assertEquals(product.sourceVersion, stored.sourceVersion)
            assertEquals(FoodAddStage.AMOUNT, state(add).stage)
        }

    /** The gap in the card survives the copy: PRD_FOOD 9.2 keeps missing values `null`. */
    @Test
    fun `the copy keeps an undocumented value unknown rather than filling it with zero`() =
        scanTest { add ->
            add.lookup.answer(
                barcode,
                ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()),
            )
            add.viewModel.onScanChosen()
            add.viewModel.onBarcodeChange(barcode)
            add.viewModel.onLookUpBarcode()
            advanceUntilIdle()

            add.viewModel.onUseScannedProduct()
            advanceUntilIdle()

            assertNull(add.foods.saved.single().per100.fibre)
        }

    // --- no product, and the four ways there is none (PRD_FOOD 17) ------------------------------

    @Test
    fun `a missing product offers the creation prefilled with the code that was looked up`() =
        scanTest { add ->
            add.lookup.answer(barcode, ProductLookupResult.NotFound)
            add.viewModel.onScanChosen()
            add.viewModel.onBarcodeChange(barcode)

            add.viewModel.onLookUpBarcode()
            advanceUntilIdle()

            val notice = assertNotNull(assertNotNull(state(add).scan).notice)
            assertTrue(notice.canCreate)
            assertEquals(barcode, add.viewModel.barcodeToCreateFrom())
        }

    /**
     * The number offered to the editor is the one that was **looked up**, not whatever is in the
     * field now. Otherwise a code corrected after a failed lookup would open a creation carrying
     * a number nobody searched for.
     */
    @Test
    fun `the code offered to the creation is the one that failed, never a later edit`() =
        scanTest { add ->
            add.lookup.answer(barcode, ProductLookupResult.NotFound)
            add.viewModel.onScanChosen()
            add.viewModel.onBarcodeChange(barcode)
            add.viewModel.onLookUpBarcode()
            advanceUntilIdle()

            // Typing again clears the outcome, so there is nothing left to create *from*.
            add.viewModel.onBarcodeChange("5000112637922")

            assertNull(add.viewModel.barcodeToCreateFrom())
        }

    @Test
    fun `each failure reaches the panel under its own name`() {
        LookupFailure.entries.forEach { reason ->
            scanTest { add ->
                add.lookup.answer(barcode, ProductLookupResult.Unavailable(reason))
                add.viewModel.onScanChosen()
                add.viewModel.onBarcodeChange(barcode)

                add.viewModel.onLookUpBarcode()
                advanceUntilIdle()

                val notice = assertNotNull(assertNotNull(state(add).scan).notice)
                assertEquals(FoodAddMessages.lookupFailure(reason), notice.message)
                assertTrue(notice.canRetry, reason.name)
                assertEquals(barcode, add.viewModel.barcodeToCreateFrom(), reason.name)
            }
        }
    }

    @Test
    fun `a retry asks again for the same number`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.Unavailable(LookupFailure.OFFLINE))
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()
        advanceUntilIdle()

        add.lookup.answer(barcode, ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()))
        add.viewModel.onRetryLookup()
        advanceUntilIdle()

        assertEquals(listOf(barcode, barcode), add.lookup.requested)
        assertNotNull(assertNotNull(state(add).scan).found)
    }

    /** A missing product cannot be retried, so nothing repeats a request whose answer is known. */
    @Test
    fun `a retry does nothing when the service simply has no such product`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.NotFound)
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()
        advanceUntilIdle()

        add.viewModel.onRetryLookup()
        advanceUntilIdle()

        assertEquals(listOf(barcode), add.lookup.requested)
    }

    // --- the camera's own events (PRD_FOOD 9.2 and 17) ------------------------------------------

    /** A decoded code lands in the field and takes the field's own road. */
    @Test
    fun `a scanned code fills the field and is looked up`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()))
        add.viewModel.onScanChosen()

        add.viewModel.onBarcodeScanned(barcode)
        advanceUntilIdle()

        val scan = assertNotNull(state(add).scan)
        assertEquals(barcode, scan.barcode)
        assertNotNull(scan.found)
        assertEquals(listOf(barcode), add.lookup.requested)
    }

    /**
     * PRD_FOOD 17 keeps the scanner running — "le scanner continue" — so a jar left in front of
     * the lens keeps producing the same digits many times a second. One request, not thirty.
     */
    @Test
    fun `a barcode held in front of the camera is looked up once`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()))
        add.viewModel.onScanChosen()

        repeat(20) { add.viewModel.onBarcodeScanned(barcode) }
        advanceUntilIdle()

        assertEquals(listOf(barcode), add.lookup.requested)
    }

    /** A result on screen is not overwritten by the next frame of the same shelf. */
    @Test
    fun `a second code is ignored while an answer is on screen`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.Found(FoodAddPreviewData.scannedProduct()))
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeScanned(barcode)
        advanceUntilIdle()

        add.viewModel.onBarcodeScanned("5000112637922")
        advanceUntilIdle()

        assertEquals(listOf(barcode), add.lookup.requested)
        assertEquals(barcode, assertNotNull(state(add).scan).barcode)
    }

    /** Clearing the field is what re-arms the scanner, so the same jar can be read again. */
    @Test
    fun `clearing the field lets the same code be scanned again`() = scanTest { add ->
        add.lookup.answer(barcode, ProductLookupResult.NotFound)
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeScanned(barcode)
        advanceUntilIdle()

        add.viewModel.onBarcodeChange("")
        add.viewModel.onBarcodeScanned(barcode)
        advanceUntilIdle()

        assertEquals(listOf(barcode, barcode), add.lookup.requested)
    }

    // --- while a request is out -----------------------------------------------------------------

    @Test
    fun `the panel says it is looking, and refuses to look twice at once`() = scanTest { add ->
        add.lookup.hold()
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()
        advanceUntilIdle()

        val scan = assertNotNull(state(add).scan)
        assertTrue(scan.isLookingUp)
        assertFalse(scan.canLookUp)

        add.lookup.release()
    }

    /**
     * Two answers arriving out of order would put the wrong product on screen, so the first
     * request is cancelled rather than left racing the second.
     */
    @Test
    fun `typing a new number while a request is out abandons the first answer`() = scanTest { add ->
        val other = "5000112637922"
        add.lookup.hold()
        add.viewModel.onScanChosen()
        add.viewModel.onBarcodeChange(barcode)
        add.viewModel.onLookUpBarcode()
        advanceUntilIdle()

        add.viewModel.onBarcodeChange(other)
        add.lookup.release()
        advanceUntilIdle()

        val scan = assertNotNull(state(add).scan)
        assertEquals(other, scan.barcode)
        assertNull(scan.found, "the abandoned request must not answer for a number nobody asked")
        assertNull(scan.notice)
    }

    // --- harness ---------------------------------------------------------------------------------

    private class Scan(
        val viewModel: FoodAddViewModel,
        val foods: RecordingFoodCatalogueRepository,
        val lookup: FakeProductLookup,
    )

    private fun scanTest(
        catalogue: List<Food> = emptyList(),
        body: suspend TestScope.(Scan) -> Unit,
    ) = runTest(mainDispatcher) {
        val foods = RecordingFoodCatalogueRepository(catalogue)
        val lookup = FakeProductLookup()
        val scan = Scan(
            viewModel = FoodAddViewModel(
                logs = FakeFoodLogRepository(emptyList()),
                foods = foods,
                recipes = FakeRecipeRepository(),
                plans = FakeMealPlanRepository(),
                lookup = lookup,
                savedState = SavedStateHandle(),
                clock = Clock.fixed(
                    FoodAddPreviewData.TODAY.atTime(FoodAddPreviewData.NOW)
                        .toInstant(ZoneOffset.UTC),
                    ZoneOffset.UTC,
                ),
                locale = { Locale.UK },
            ),
            foods = foods,
            lookup = lookup,
        )

        val collector = launch { scan.viewModel.uiState.collect { } }
        advanceUntilIdle()
        scan.viewModel.start(date = null, slot = null, entryId = null)
        advanceUntilIdle()

        body(scan)

        collector.cancel()
    }

    private fun TestScope.state(scan: Scan): FoodAddUiState {
        advanceUntilIdle()
        return scan.viewModel.uiState.value
    }
}
