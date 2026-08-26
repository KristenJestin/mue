package fr.kristenjestin.mue.data.remote.openfoodfacts

import kotlin.test.assertNotNull

/**
 * The recorded Open Food Facts bodies these tests read, and the only source of data in them.
 *
 * Every JSON file under `src/test/resources/openfoodfacts` was **recorded** from the live
 * service on 26 August 2026, with the request [OpenFoodFactsUrl.productRequest] builds — same
 * host, same pinned minor version, same `fields` projection. Nothing is invented, so a mapper
 * that passes here is a mapper that survives the answers real barcodes produce.
 *
 * No test in this package opens a socket. That is the point: PRD_FOOD 20.2 isolates the one
 * network call behind a replaceable interface, and everything on this side of the interface —
 * the URL and the JSON — is a pure function that a phone in flight mode can verify.
 */
object OpenFoodFactsFixtures {

    /**
     * Nutella, barcode 3017620422003, revision 947.
     *
     * The flagship card, and the one that states the problem: energy, protein, carbohydrate and
     * fat come from the manufacturer, while **fibre carries `"source": "estimate"`** — a figure
     * Open Food Facts computed from the ingredient list rather than read off a label.
     */
    const val FOUND: String = "product-found.json"

    /**
     * Marmite yeast extract, barcode 50184453, revision 144.
     *
     * The incomplete card. Its aggregated set holds energy, protein and carbohydrate and simply
     * has **no `fat` entry and no `fiber` entry at all**, which is the ordinary state of a
     * third-party record and the case PRD_FOOD 9.2 calls nominal.
     */
    const val INCOMPLETE: String = "product-incomplete.json"

    /**
     * Coca-Cola, barcode 5000112637922, revision 50.
     *
     * Known zeros and an unknown, in one real card: protein and fat are documented as `0`, fibre
     * is absent. It is the fixture that makes `0` and `null` impossible to confuse.
     */
    const val KNOWN_ZEROS: String = "product-known-zeros.json"

    /**
     * [INCOMPLETE] with its `energy-kcal` entry removed, leaving only the generic `energy` in
     * kilojoules.
     *
     * The one derived fixture, and it is derived because the case cannot be recorded: Open Food
     * Facts computes the kilocalorie figure itself whenever a card states kilojoules, so no live
     * v3.6 card is kilojoule-only. The fallback still has to exist and still has to be right, so
     * the fixture reproduces the shape by deletion rather than by invention.
     */
    const val KILOJOULES_ONLY: String = "product-kilojoules-only.json"

    /** The recorded 404 body for barcode 3596710352975: `status: failure`, `product_not_found`. */
    const val NOT_FOUND: String = "product-not-found.json"

    /** A stated failure that is not a missing product. */
    const val SERVICE_FAILURE: String = "response-service-failure.json"

    /** [FOUND], cut off after 512 bytes: a connection that died mid-body. */
    const val TRUNCATED: String = "response-truncated.json"

    /**
     * The recorded "Page temporarily unavailable" HTML Open Food Facts serves under load.
     *
     * A real answer, with a real content type, that is not JSON at all — the failure a client
     * meets far more often than a corrupted product card.
     */
    const val NOT_JSON: String = "response-not-json.html"

    /** The barcode each recorded card was fetched with. */
    const val FOUND_BARCODE: String = "3017620422003"

    const val INCOMPLETE_BARCODE: String = "50184453"

    const val KNOWN_ZEROS_BARCODE: String = "5000112637922"

    const val NOT_FOUND_BARCODE: String = "3596710352975"

    fun read(name: String): String {
        val stream = OpenFoodFactsFixtures::class.java.classLoader
            ?.getResourceAsStream("openfoodfacts/$name")
        assertNotNull(stream, "missing fixture openfoodfacts/$name")
        return stream.bufferedReader().use { it.readText() }
    }
}
