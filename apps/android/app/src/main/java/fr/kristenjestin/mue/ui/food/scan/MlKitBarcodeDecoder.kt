package fr.kristenjestin.mue.ui.food.scan

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The decoder itself: an image in, a retail barcode out, and **nothing leaves the phone**.
 *
 * PRD_FOOD 9.2 is unambiguous — "Le décodage est **local** : la caméra lit le numéro avec ML Kit,
 * aucune image ne quitte le téléphone" — and the artefact chosen in `build.gradle.kts` is what
 * makes that true rather than merely intended: `com.google.mlkit:barcode-scanning` carries the
 * model inside the APK, so a phone in flight mode decodes exactly as well as one on wifi and
 * there is no download, no Play services call and no upload on this path.
 *
 * ## Which formats, and why not all of them
 *
 * [OPTIONS] names five, and the restriction is a decision rather than a saving. ML Kit will
 * happily read the QR code printed beside the EAN on most packaging today, the Data Matrix on a
 * pharmacy box and the Code 128 on the shelf edge — none of which is a number Open Food Facts can
 * be asked about, and every one of which would make the scanner "find" something wrong while the
 * jar in the shopper's hand is ignored. Restricting the formats also makes the detector faster,
 * because it stops looking for the ones that cannot be the answer.
 *
 * The five are the retail family: EAN-13 and EAN-8 (Europe), UPC-A and UPC-E (North America, and
 * routinely on imported products), and ITF for the GTIN-14 cases outers carry. Together they are
 * exactly what `Food.BARCODE_LENGTH_RANGE`'s eight-to-fourteen digits describe.
 *
 * ## What a UPC-A gives back, measured on a device
 *
 * An EAN-13 beginning with `0` **is** a UPC-A, and ML Kit says so: it reports `FORMAT_UPC_A` and
 * a `rawValue` of the **twelve** digits printed under the bars, not the thirteen the symbol
 * encodes. `MlKitBarcodeDecoderTest` asserts it rather than assuming it, because the whole chain
 * behind this file already handles it and it would be easy to "fix" by mistake:
 *
 * - twelve digits are inside `Food.BARCODE_LENGTH_RANGE`, so the request goes out as it is;
 * - Open Food Facts normalises the code and returns the canonical one in `product.code`, which
 *   [fr.kristenjestin.mue.data.remote.openfoodfacts.OpenFoodFactsMapper] keeps as `sourceId`
 *   while `barcode` keeps what was actually scanned;
 * - so re-scanning finds the copied row by its barcode, and the thirteen-digit form — typed by
 *   hand, or filed upstream — is found beside it by its source id.
 *
 * Padding a zero on here would break that symmetry rather than restore it: the number under the
 * bars is what somebody reads out and types, and it is the number this app should hold.
 */
internal object MlKitBarcodeDecoder {

    /** The retail formats, and no others. See the class doc for why the list is short. */
    val OPTIONS: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_ITF,
        )
        .build()

    /** A scanner configured with [OPTIONS]. The caller owns it and must `close()` it. */
    fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(OPTIONS)

    /**
     * One still image decoded, for the test that proves this file does what it says.
     *
     * A camera cannot run in a test and a virtual scene cannot be made to hold a jar of Nutella,
     * so the honest thing to assert on a device is this: a bitmap that really encodes an EAN-13,
     * handed to the real ML Kit detector, comes back as the digits it was built from. That is a
     * decode, not a mock — and it is the only part of the camera path a machine can check.
     *
     * Nothing in production calls it: the live path goes frame by frame through
     * [BarcodeFrameAnalyzer], which cannot suspend.
     */
    @VisibleForTesting
    suspend fun decodeOrNull(bitmap: Bitmap): String? {
        val scanner = newScanner()
        return try {
            val barcodes = suspendCancellableCoroutine { continuation ->
                scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            FoodBarcodes.firstRetailOrNull(barcodes.map(Barcode::getRawValue))
        } finally {
            scanner.close()
        }
    }
}
