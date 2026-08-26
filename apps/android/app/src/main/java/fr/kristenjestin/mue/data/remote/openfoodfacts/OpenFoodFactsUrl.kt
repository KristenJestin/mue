package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.model.Food

/**
 * Everything Mue sends to Open Food Facts, built here and nowhere else.
 *
 * PRD_FOOD 9.2 and 22 make a promise about a scan: the number is decoded on the phone by ML Kit,
 * no image leaves it, and "seul le numéro est transmis". That is a claim about the **whole
 * request**, not about its path alone — a header, a cookie or a locale parameter would break it
 * just as surely as a query string would. So this object hands back the URL *and* the headers as
 * one [OpenFoodFactsRequest]: a transport that only forwards that value has nothing left to add,
 * and the promise stops being a code-review habit and becomes a unit test.
 *
 * There is no HTTP client here on purpose. The request is a string and a map, which is why the
 * whole of PRD_FOOD 22's "seul le numéro est transmis" can be asserted offline.
 *
 * ## The path
 *
 * `GET /api/v3.6/product/{barcode}` on `world.openfoodfacts.org`, with **no `.json` suffix**:
 * that is the shape the Open Food Facts OpenAPI reference defines for v3
 * (`docs/api/ref/api-v3.yaml`, path `"/api/v3/product/{code}"`, server `world.openfoodfacts.org`
 * for production and `world.openfoodfacts.net` for their development instance). The `.json`
 * suffix is a v0/v2 habit that the v3 router still tolerates; it is not what the specification
 * says, so it is not what Mue sends.
 *
 * The minor version is **pinned**, and that is not cosmetic. Open Food Facts serves a different
 * product schema per minor: up to v3.4 the nutrition facts arrive as a flat `nutriments` map
 * (`energy-kcal_100g`, `proteins_100g`, …), while from v3.5 onwards that map is returned *empty*
 * and the facts move into a `nutrition` object carrying an `aggregated_set`. An unversioned
 * `/api/v3/` currently answers like v3.4 and will move without warning — which would silently
 * empty every nutrient Mue reads. PRD_FOOD 23 arbitrates "API v3.6", [API_VERSION] is that
 * arbitration, and [OpenFoodFactsMapper] reads the schema it names.
 */
object OpenFoodFactsUrl {

    /** The production server of the Open Food Facts OpenAPI reference. */
    const val HOST: String = "world.openfoodfacts.org"

    /** PRD_FOOD 23: "Open Food Facts, API v3.6". Pinned, for the reason the class doc gives. */
    const val API_VERSION: String = "v3.6"

    const val USER_AGENT_HEADER: String = "User-Agent"

    /**
     * The one header Mue sends, and a constant rather than anything computed.
     *
     * Open Food Facts asks every client to identify itself so its traffic is not mistaken for a
     * bot, in the form `AppName/Version (Contact)`. The contact half is the Android application
     * id: it names the application without naming its user, and it stays identical on every
     * phone. Nothing here is derived from the device, the account, the locale or the build, so
     * two people scanning the same product send byte-identical requests.
     */
    const val USER_AGENT: String = "Mue/1.0 (Android; fr.kristenjestin.mue)"

    const val FIELDS_PARAMETER: String = "fields"

    /**
     * The explicit projection Mue asks for, so the answer is small and its shape is a decision
     * rather than a default.
     *
     * Open Food Facts returns roughly two hundred fields when `fields` is omitted — knowledge
     * panels, Eco-Score, taxonomies, editor names. Asking for eight of them is not only bandwidth
     * on a phone: it is the list a reviewer reads to see exactly what Mue takes from a third
     * party, and it is the list PRD_FOOD 9.2 names — "nom, marque, image et nutriments pour
     * 100 g" — plus the two fields provenance needs (`code`, `rev`) and the usual portion.
     */
    val FIELDS: List<String> = listOf(
        // The canonical barcode of the card, which is not always the number that was scanned.
        "code",
        "product_name",
        "brands",
        // The v3.5+ nutrition object; see the class doc on why the minor version is pinned.
        "nutrition",
        "serving_size",
        "serving_quantity",
        // PRD_FOOD 14: an Open Food Facts image is kept as a remote URL, never copied into Room.
        "image_front_url",
        // PRD_FOOD 16.3: the revision of the remote card, which becomes `Food.sourceVersion`.
        "rev",
    )

    /**
     * The request for one barcode, or null when the argument is not a barcode.
     *
     * The guard is what makes the URL safe to build by concatenation. A barcode that reached
     * this point is digits only and 8 to 14 of them ([Food.BARCODE_LENGTH_RANGE], EAN-8 through
     * GTIN-14), so there is nothing in it that percent-encoding would change, nothing that could
     * close the path and open a query, and nothing that could smuggle a second parameter. Text
     * typed by hand when the camera is refused (PRD_FOOD 18) goes through the same door.
     */
    fun productRequest(barcode: String): OpenFoodFactsRequest? {
        if (!isBarcode(barcode)) return null
        return OpenFoodFactsRequest(
            url = "https://$HOST/api/$API_VERSION/product/$barcode" +
                "?$FIELDS_PARAMETER=${FIELDS.joinToString(",")}",
            headers = mapOf(USER_AGENT_HEADER to USER_AGENT),
        )
    }

    /** Digits only, and as many of them as a retail barcode has. */
    fun isBarcode(candidate: String): Boolean =
        candidate.length in Food.BARCODE_LENGTH_RANGE && candidate.all { it in '0'..'9' }
}

/**
 * One outgoing request, complete.
 *
 * It exists so the transport chunk has no decision left to make and no header of its own to add,
 * and so a test can assert that two different barcodes produce two requests differing in exactly
 * one place — the number. That assertion is PRD_FOOD 22's "seul le numéro est transmis", checked
 * without a socket.
 */
data class OpenFoodFactsRequest(
    val url: String,
    val headers: Map<String, String>,
)
