package fr.kristenjestin.mue.ui.food.scan

import fr.kristenjestin.mue.data.remote.openfoodfacts.OpenFoodFactsUrl

/**
 * Which of the codes a frame contains is the one the shopper meant (PRD_FOOD 9.2).
 *
 * A **pure function of a list of strings**, with no ML Kit type anywhere in the signature, so the
 * rule below is proved on the JVM from a list literal while the decoder that produces those
 * strings is proved on a device from a real image. Splitting the two is what makes each half
 * testable at all: a camera cannot run in a unit test, and a choice among candidates does not
 * need one.
 */
internal object FoodBarcodes {

    /**
     * The first candidate that is a retail barcode, or null when the frame held none.
     *
     * "Retail barcode" is [OpenFoodFactsUrl.isBarcode]'s definition and no other — eight to
     * fourteen digits — because that is the one Open Food Facts can be asked about and the one
     * `Food.barcode` stores. Reusing it rather than restating it is what stops the camera from
     * accepting a number the typed field would refuse, or the reverse.
     *
     * A frame routinely holds more than one code: a shelf edge label beside the jar, a loyalty
     * card in the same hand, the QR code most packaging now carries next to the EAN. The QR and
     * the shelf label are excluded by the format filter the decoder applies; anything that gets
     * past it and is not a retail number is dropped here. Order is the decoder's — ML Kit returns
     * codes roughly by position — and taking the first is the only honest tie-break available:
     * nothing in a list of digits says which object was being held.
     *
     * Nulls are accepted in the input because `Barcode.rawValue` is nullable: a code ML Kit
     * located but could not read is a real outcome, and PRD_FOOD 17 answers it by saying the
     * scanner simply keeps going.
     */
    fun firstRetailOrNull(candidates: List<String?>): String? =
        candidates.asSequence()
            .filterNotNull()
            .map(String::trim)
            .firstOrNull(OpenFoodFactsUrl::isBarcode)
}
