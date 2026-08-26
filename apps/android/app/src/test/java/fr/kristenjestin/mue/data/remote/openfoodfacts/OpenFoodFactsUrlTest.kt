package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.model.Food
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD_FOOD 22: "Le décodage du code-barres est local et **seul le numéro est transmis**."
 *
 * That acceptance criterion is usually checked by reading the networking code and hoping. Here
 * the request is a value, so it is checked by equality — twice over: once against the literal
 * URL, and once by building two requests and proving they differ in exactly one place.
 */
class OpenFoodFactsUrlTest {

    private val barcode = "3017620422003"

    @Test
    fun `the request is the documented v3 path, the barcode and one fields parameter`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))

        assertEquals(
            "https://world.openfoodfacts.org/api/v3.6/product/3017620422003" +
                "?fields=code,product_name,brands,nutrition,serving_size,serving_quantity," +
                "image_front_url,rev",
            request.url,
        )
    }

    @Test
    fun `the only header is the static User-Agent`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))

        assertEquals(mapOf("User-Agent" to "Mue/1.0 (Android; fr.kristenjestin.mue)"), request.headers)
    }

    /** The whole of "seul le numéro est transmis", as one assertion. */
    @Test
    fun `two barcodes produce two requests differing only by the number`() {
        val first = assertNotNull(OpenFoodFactsUrl.productRequest("3017620422003"))
        val second = assertNotNull(OpenFoodFactsUrl.productRequest("5000112637922"))

        assertEquals(first.headers, second.headers)
        assertEquals(
            first.url.replace("3017620422003", "{barcode}"),
            second.url.replace("5000112637922", "{barcode}"),
        )
    }

    @Test
    fun `a shorter barcode changes nothing but its own segment`() {
        val long = assertNotNull(OpenFoodFactsUrl.productRequest("3017620422003"))
        val short = assertNotNull(OpenFoodFactsUrl.productRequest("50184453"))

        assertEquals(long.url.replace("3017620422003", ""), short.url.replace("50184453", ""))
    }

    @Test
    fun `the path is the pinned api version and the barcode, and nothing else`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))
        val path = request.url.substringAfter("https://world.openfoodfacts.org").substringBefore('?')

        assertEquals("/api/v3.6/product/$barcode", path)
    }

    @Test
    fun `the query string carries exactly one parameter, and it is fields`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))
        val query = request.url.substringAfter('?')

        assertEquals(1, request.url.count { it == '?' })
        assertFalse(query.contains('&'), "a second parameter would be a second thing transmitted")
        assertEquals(OpenFoodFactsUrl.FIELDS_PARAMETER, query.substringBefore('='))
    }

    @Test
    fun `the url carries no fragment, no credential and no escape`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))

        assertFalse(request.url.contains('#'))
        assertFalse(request.url.contains('@'))
        assertFalse(request.url.contains('%'))
        assertFalse(request.url.contains(' '))
    }

    @Test
    fun `the fields list is explicit, and it is the eight PRD_FOOD 9-2 and 16-3 need`() {
        assertEquals(
            listOf(
                "code",
                "product_name",
                "brands",
                "nutrition",
                "serving_size",
                "serving_quantity",
                "image_front_url",
                "rev",
            ),
            OpenFoodFactsUrl.FIELDS,
        )
    }

    @Test
    fun `the fields list has no duplicate and no empty entry`() {
        assertEquals(OpenFoodFactsUrl.FIELDS.size, OpenFoodFactsUrl.FIELDS.toSet().size)
        assertTrue(OpenFoodFactsUrl.FIELDS.all { it.isNotBlank() })
        assertTrue(OpenFoodFactsUrl.FIELDS.none { it.contains(',') || it.contains('&') })
    }

    /** PRD_FOOD 23 arbitrates the version, and a moving default would change the payload shape. */
    @Test
    fun `the host and the api version are the ones PRD_FOOD 23 names`() {
        assertEquals("world.openfoodfacts.org", OpenFoodFactsUrl.HOST)
        assertEquals("v3.6", OpenFoodFactsUrl.API_VERSION)
    }

    /** The v3 reference defines `/api/v3/product/{code}`; the `.json` suffix is a v2 habit. */
    @Test
    fun `the path carries no json suffix`() {
        val request = assertNotNull(OpenFoodFactsUrl.productRequest(barcode))

        assertFalse(request.url.substringBefore('?').endsWith(".json"))
    }

    @Test
    fun `every retail barcode length is accepted`() {
        Food.BARCODE_LENGTH_RANGE.forEach { length ->
            val candidate = "1".repeat(length)
            assertNotNull(OpenFoodFactsUrl.productRequest(candidate), "$length digits")
        }
    }

    @Test
    fun `a number that is not a retail barcode is refused`() {
        val tooShort = "1".repeat(Food.BARCODE_LENGTH_RANGE.first - 1)
        val tooLong = "1".repeat(Food.BARCODE_LENGTH_RANGE.last + 1)

        assertNull(OpenFoodFactsUrl.productRequest(tooShort))
        assertNull(OpenFoodFactsUrl.productRequest(tooLong))
        assertNull(OpenFoodFactsUrl.productRequest(""))
    }

    /**
     * The guard that lets the URL be built by concatenation: nothing but digits gets through, so
     * nothing can close the path, open a second parameter or need escaping.
     */
    @Test
    fun `anything that is not digits never becomes a url`() {
        val refused = listOf(
            "301762042200a",
            "3017620422003 ",
            " 3017620422003",
            "3017620422-03",
            "30176204?2003",
            "30176204&2003",
            "3017620/422003",
            "301762..22003",
            "30176204#2003",
            "3017620%422003",
            "٣٠١٧٦٢٠٤٢٢٠٠٣",
            "3017620422003\n",
        )

        refused.forEach { assertNull(OpenFoodFactsUrl.productRequest(it), it) }
    }

    @Test
    fun `isBarcode agrees with productRequest on every candidate`() {
        val candidates = listOf(
            "3017620422003",
            "50184453",
            "5000112637922",
            "1234567",
            "123456789012345",
            "abcdefgh",
            "",
        )

        candidates.forEach {
            assertEquals(
                OpenFoodFactsUrl.isBarcode(it),
                OpenFoodFactsUrl.productRequest(it) != null,
                it,
            )
        }
    }

    /** No clock, no locale, no device: the same number produces the same bytes every time. */
    @Test
    fun `the request is a pure function of the barcode`() {
        assertEquals(
            OpenFoodFactsUrl.productRequest(barcode),
            OpenFoodFactsUrl.productRequest(barcode),
        )
    }
}
