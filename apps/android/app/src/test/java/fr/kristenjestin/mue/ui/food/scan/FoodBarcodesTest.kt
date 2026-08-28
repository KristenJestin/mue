package fr.kristenjestin.mue.ui.food.scan

import fr.kristenjestin.mue.domain.model.Food
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which code in a frame is the one the shopper meant (PRD_FOOD 9.2).
 *
 * The decoder cannot run here and does not need to: what it produces is a list of strings, and
 * the choice among them is the part with a rule in it. The rule is
 * [fr.kristenjestin.mue.data.remote.openfoodfacts.OpenFoodFactsUrl.isBarcode]'s and no other, so
 * the camera can never accept a number the typed field would refuse.
 */
class FoodBarcodesTest {

    @Test
    fun `a single retail barcode is the answer`() {
        assertEquals("3017620422003", FoodBarcodes.firstRetailOrNull(listOf("3017620422003")))
    }

    @Test
    fun `nothing at all is null rather than an empty string`() {
        assertNull(FoodBarcodes.firstRetailOrNull(emptyList()))
    }

    /** `Barcode.rawValue` is nullable: a code ML Kit located but could not read is a real case. */
    @Test
    fun `a located but unreadable code is skipped`() {
        assertEquals(
            "3017620422003",
            FoodBarcodes.firstRetailOrNull(listOf(null, "3017620422003")),
        )
    }

    /**
     * Most packaging now carries a QR beside the EAN, and a shelf edge label carries a Code 128.
     * Neither is a number Open Food Facts can be asked about.
     */
    @Test
    fun `a url or a lot code in the same frame is ignored`() {
        assertEquals(
            "3017620422003",
            FoodBarcodes.firstRetailOrNull(
                listOf("https://ferrero.example/nutella", "LOT-2026-08", "3017620422003"),
            ),
        )
    }

    @Test
    fun `nothing retail in the frame is null`() {
        assertNull(FoodBarcodes.firstRetailOrNull(listOf("https://example.org", "ABC123456789")))
    }

    @Test
    fun `surrounding whitespace is trimmed rather than making a code unreadable`() {
        assertEquals(
            "3017620422003",
            FoodBarcodes.firstRetailOrNull(listOf("  3017620422003 ")),
        )
    }

    @Test
    fun `every retail length the catalogue stores is accepted`() {
        Food.BARCODE_LENGTH_RANGE.forEach { length ->
            val candidate = "1".repeat(length)
            assertEquals(candidate, FoodBarcodes.firstRetailOrNull(listOf(candidate)), "$length")
        }
    }

    @Test
    fun `a number too short or too long to be a retail barcode is refused`() {
        assertNull(
            FoodBarcodes.firstRetailOrNull(
                listOf("1".repeat(Food.BARCODE_LENGTH_RANGE.first - 1)),
            ),
        )
        assertNull(
            FoodBarcodes.firstRetailOrNull(
                listOf("1".repeat(Food.BARCODE_LENGTH_RANGE.last + 1)),
            ),
        )
    }

    /** Order is the decoder's, and taking the first is the only honest tie-break available. */
    @Test
    fun `the first retail code wins when a frame holds two`() {
        assertEquals(
            "3017620422003",
            FoodBarcodes.firstRetailOrNull(listOf("3017620422003", "5000112637922")),
        )
    }
}
