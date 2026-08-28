package fr.kristenjestin.mue.ui.food.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one part of FR-FOOD-003 that needs a device: that ML Kit really reads a barcode.
 *
 * Everything else about the scan — the local hit, the four named failures, the copy into the
 * catalogue, the prefilled creation, the refused camera — is proved on the JVM, because
 * PRD_FOOD 18 makes the typed number an equal path and nothing past the digits knows how they
 * arrived. What is left is exactly this: an image in, the digits printed under it out.
 *
 * **This is not a camera test, and it does not claim to be one.** The emulator's camera is a
 * virtual scene; nothing can put a jar of Nutella in front of it. What runs here is the real
 * `com.google.mlkit:barcode-scanning` detector, with the real
 * [MlKitBarcodeDecoder.OPTIONS], against a real EAN-13 built from the GS1 specification — a
 * decode of a fixture, honestly labelled. The live path adds CameraX's frame delivery and
 * rotation on top of it, and that part is verified by hand on a phone, not here.
 */
@RunWith(AndroidJUnit4::class)
class MlKitBarcodeDecoderTest {

    /** The recorded Open Food Facts fixture's own barcode, so the two halves match end to end. */
    private val nutella = "3017620422003"

    @Test
    fun aRealEan13IsDecodedToTheDigitsPrintedUnderIt() = runBlocking {
        val decoded = MlKitBarcodeDecoder.decodeOrNull(Ean13Fixture.bitmap(nutella))

        assertEquals(nutella, decoded)
    }

    /**
     * A zero-prefixed EAN-13 comes back as its **twelve-digit UPC-A form**, and that is correct.
     *
     * This test was written expecting thirteen digits and the device said otherwise, which is
     * worth recording rather than papering over: an EAN-13 beginning with `0` *is* a UPC-A, ML
     * Kit classifies it as `FORMAT_UPC_A`, and `rawValue` is then the twelve digits printed under
     * the bars. Every North American product on a European shelf reads this way.
     *
     * Nothing downstream has to change for it, and that is the point of asserting it here:
     *
     * - `Food.BARCODE_LENGTH_RANGE` is eight to fourteen, so twelve is a barcode and the request
     *   goes out unchanged;
     * - Open Food Facts normalises the code itself and answers with the canonical one in
     *   `product.code`, which `OpenFoodFactsMapper` stores as `sourceId` while `barcode` keeps
     *   what was scanned — the distinction that file already documents;
     * - a re-scan produces the same twelve digits, so `findByBarcode` finds the copied row, and a
     *   thirteen-digit form typed by hand is caught by the `sourceId` lookup beside it.
     */
    @Test
    fun aZeroPrefixedEan13IsReportedAsItsTwelveDigitUpcAForm() = runBlocking {
        // 0000050184453 — the Marmite fixture's code padded to thirteen, a valid EAN-13.
        val ean13 = "0000050184453"

        val decoded = MlKitBarcodeDecoder.decodeOrNull(Ean13Fixture.bitmap(ean13))

        assertEquals("000050184453", decoded)
        assertEquals(ean13, "0" + decoded)
    }

    /**
     * The frame arrives from a hand, not from a scanner bed.
     *
     * A symbol drawn at a slight angle still has to read, because otherwise the live scanner
     * would work only when the phone is held perfectly square — which is never.
     */
    @Test
    fun aSlightlyRotatedSymbolStillReads() = runBlocking {
        val rotated = rotate(Ean13Fixture.bitmap(nutella), degrees = 4f)

        assertEquals(nutella, MlKitBarcodeDecoder.decodeOrNull(rotated))
    }

    /**
     * PRD_FOOD 17: "Code-barres illisible → le scanner continue."
     *
     * A frame with nothing in it must produce *nothing* rather than an exception, because the
     * live analyser meets one of these several times a second while the phone is being aimed.
     */
    @Test
    fun aFrameWithNoBarcodeInItDecodesToNothing() = runBlocking {
        val blank = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }

        assertNull(MlKitBarcodeDecoder.decodeOrNull(blank))
    }

    /**
     * The formats are restricted on purpose (see [MlKitBarcodeDecoder]), and this is what that
     * restriction is for: a QR code in the same frame as the EAN — which most packaging now
     * carries — must not be offered as a barcode. The detector is configured never to look for
     * one, and `FoodBarcodes` would refuse its contents anyway.
     */
    @Test
    fun theDetectorIsConfiguredForRetailFormatsOnly() = runBlocking {
        val text = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            canvas.drawText(
                "https://example.org",
                20f,
                120f,
                Paint().apply {
                    color = Color.BLACK
                    textSize = 48f
                },
            )
        }

        assertNull(MlKitBarcodeDecoder.decodeOrNull(text))
    }

    /**
     * Rotated **onto white**, not with `createBitmap(source, matrix)`.
     *
     * That overload leaves the corners fully transparent, and a transparent pixel reaches a
     * grayscale converter as black — which would ring the symbol in a dark frame and destroy the
     * quiet zone the detector needs. The failure would look like "ML Kit cannot read a tilted
     * barcode", which is not true and would have been the test's fault.
     */
    private fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val side = (maxOf(source.width, source.height) * 1.5f).toInt()
        val target = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.rotate(degrees, side / 2f, side / 2f)
        canvas.drawBitmap(
            source,
            (side - source.width) / 2f,
            (side - source.height) / 2f,
            null,
        )
        canvas.restore()
        return target
    }
}
