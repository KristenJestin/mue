package fr.kristenjestin.mue.data.remote.openfoodfacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Open Food Facts v3.6 answer, exactly as it arrives.
 *
 * These types are the wire, not the domain: nothing here is validated, nothing is converted and
 * every field is optional, because a collaborative database run by someone else is allowed to
 * omit anything at any time (PRD_FOOD 13.3 accepts that as a fact of the domain, not a defect).
 * The whole of the judgement lives one file away in [OpenFoodFactsMapper], so the shape of the
 * JSON and the rules Mue applies to it can be read, and changed, separately.
 *
 * The envelope is v3's own: a `status`, a `result` naming what happened, and a `product` that is
 * present only when there is one. A missing product is therefore a fact stated by the body, not
 * inferred from an HTTP code — which is what lets the mapper be a pure function of the text.
 */
@Serializable
data class OpenFoodFactsResponse(
    /** `success`, `success_with_warnings`, `success_with_errors` or `failure`. */
    val status: String? = null,
    val result: OpenFoodFactsResult? = null,
    val product: OpenFoodFactsProduct? = null,
    /** The code as Open Food Facts normalised it, which can differ from the one requested. */
    val code: String? = null,
) {
    companion object {

        const val STATUS_FAILURE: String = "failure"

        /** The `result.id` Open Food Facts returns for a barcode it has no card for. */
        const val RESULT_PRODUCT_NOT_FOUND: String = "product_not_found"

        /**
         * Unknown keys are ignored, and that is the single most important line in this file.
         *
         * Mue asks for eight fields and Open Food Facts answers with those eight plus whatever
         * else it feels like — `nutriments_estimated`, `nutrition_data_prepared_per`,
         * `errors`, `warnings`, and new fields added between two releases. PRD_FOOD 20.2 isolates
         * this call behind a replaceable interface precisely so a remote schema change degrades
         * instead of breaking; refusing an unexpected key would turn every such change into a
         * scanner that no longer works.
         */
        private val format = Json { ignoreUnknownKeys = true }

        /**
         * Total and non-throwing: a body that is not this shape comes back null, and the mapper
         * turns that into `LookupFailure.MALFORMED_RESPONSE` rather than into an exception
         * crossing a coroutine boundary. Truncated JSON, an HTML outage page and an empty body
         * all land here.
         */
        fun fromJsonOrNull(raw: String): OpenFoodFactsResponse? = try {
            format.decodeFromString(serializer(), raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** What the request did, in Open Food Facts' own words. */
@Serializable
data class OpenFoodFactsResult(
    val id: String? = null,
    val name: String? = null,
)

/** One product card, restricted to the fields [OpenFoodFactsUrl.FIELDS] asks for. */
@Serializable
data class OpenFoodFactsProduct(
    val code: String? = null,
    @SerialName("product_name") val productName: String? = null,
    /** A **comma separated list**: `"Nutella, Ferrero, Yum yum"`. */
    val brands: String? = null,
    /** The revision of the card, incremented by every edit; PRD_FOOD 16.3's provenance. */
    val rev: Long? = null,
    @SerialName("image_front_url") val imageFrontUrl: String? = null,
    /** Free text as printed: `"330 g"`, `"8 gram"`, `"1 serving (100 g)"`. */
    @SerialName("serving_size") val servingSize: String? = null,
    /** The same portion as a number. Quoted in some records and bare in others. */
    @SerialName("serving_quantity") val servingQuantity: JsonPrimitive? = null,
    val nutrition: OpenFoodFactsNutrition? = null,
)

/**
 * The nutrition facts as API v3.5 and above return them.
 *
 * `input_sets` — one entry per source and per reference quantity, including the per-serving one —
 * is deliberately absent from this type. Mue needs values for 100 g (PRD_FOOD 8.2), and
 * [aggregatedSet] is the only set Open Food Facts normalises to that basis.
 */
@Serializable
data class OpenFoodFactsNutrition(
    @SerialName("aggregated_set") val aggregatedSet: OpenFoodFactsNutrientSet? = null,
)

/**
 * The combined set: one value per nutrient, taken from the best source available and normalised
 * to a 100 g or 100 ml basis.
 */
@Serializable
data class OpenFoodFactsNutrientSet(
    /** `100g` or `100ml` — the basis the values are quoted against. */
    val per: String? = null,
    /** `as_sold` or `prepared`. */
    val preparation: String? = null,
    /**
     * Keyed by the Open Food Facts nutrient taxonomy: `energy-kcal`, `proteins`,
     * `carbohydrates`, `fat`, `fiber`, and around forty more Mue does not read.
     */
    val nutrients: Map<String, OpenFoodFactsNutrient> = emptyMap(),
)

/**
 * One nutrient of one set.
 *
 * [source] is the field this module exists to respect. Open Food Facts states, per nutrient,
 * whether the number came from the manufacturer, from the packaging, from a reference table, or
 * from its own `estimate` computed out of the ingredient list. PRD_FOOD 17 is unambiguous about
 * the last one — missing values are "jamais estimées" — so a nutrient that says `estimate` is a
 * nutrient Mue does not know.
 */
@Serializable
data class OpenFoodFactsNutrient(
    val value: JsonPrimitive? = null,
    /** `g`, `kcal`, `kJ`, `%`, `% vol`, or empty. Read, never assumed; see the mapper. */
    val unit: String? = null,
    /** `manufacturer`, `packaging`, `usda` or `estimate`. */
    val source: String? = null,
    /** `~`, `<` or `>` when the label qualifies the figure. */
    val modifier: String? = null,
)

/**
 * A wire number as a `Double`, or null when there is not one.
 *
 * Open Food Facts quotes the same field as `330` in one record and `"330"` in another, so the
 * DTO holds the raw primitive and the conversion happens once, here. JSON `null`, an empty
 * string and anything that is not a finite number are all *unknown* — never zero, which
 * PRD_FOOD 13.1 forbids anything in this module from inventing.
 */
internal fun JsonPrimitive?.asFiniteDoubleOrNull(): Double? {
    if (this == null || this is JsonNull) return null
    return content.toDoubleOrNull()?.takeIf { it.isFinite() }
}
