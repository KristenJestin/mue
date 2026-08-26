package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.Food

/**
 * The one network call the Food module makes: a barcode to Open Food Facts (PRD_FOOD 9.2).
 *
 * It is an interface in the domain so the whole module compiles and is tested without it, which
 * PRD_FOOD 20.2 requires in as many words — the remote call is isolated behind a replaceable
 * interface and no other path may depend on it. Decoding happens on the phone with ML Kit and no
 * image ever leaves it; only the number crosses, and PRD_FOOD 20.2 sends no personal data with it.
 */
interface ProductLookup {

    /** Looks a barcode up. Never throws: every outcome, including a failure, is a value. */
    suspend fun byBarcode(barcode: String): ProductLookupResult
}

/**
 * What a lookup found (PRD_FOOD 9.2 and 17).
 *
 * The three cases exist because PRD_FOOD 17 gives them three different screens, and a nullable
 * result would collapse two of them into one. "This product is not in Open Food Facts" leads to
 * a manual creation prefilled with the barcode — an ordinary, successful path. "The network is
 * not available" leads to an explicit message and an invitation to try again, with the three
 * other ways of adding a line left untouched. Telling a person to type a product in because a
 * train went into a tunnel is the failure this type exists to prevent.
 */
sealed interface ProductLookupResult {

    /**
     * A product card was returned.
     *
     * [food] is a **candidate**, not a catalogue entry: it already carries a fresh `FoodId`,
     * `source = OPEN_FOOD_FACTS`, its barcode and its source id, and it is written to the
     * catalogue only when the person adds it (PRD_FOOD 9.2). Values the card does not document
     * stay null — PRD_FOOD 9.2 accepts an incomplete card and never guesses.
     */
    data class Found(val food: Food) : ProductLookupResult

    /** The service answered, and knows no such barcode. PRD_FOOD 17 prefills a manual creation. */
    data object NotFound : ProductLookupResult

    /** The service could not be reached or could not be understood. Nothing is known either way. */
    data class Unavailable(val reason: LookupFailure) : ProductLookupResult
}

/**
 * Why a lookup could not answer (PRD_FOOD 17: "message explicite").
 *
 * Never persisted and never synchronised — it lives as long as one scan — which is why, alone
 * among the enums of this module, it carries no stable id and no `fromId`.
 */
enum class LookupFailure {
    /** No usable connection at all. */
    OFFLINE,

    /** A connection that never answered in time. */
    TIMEOUT,

    /** Open Food Facts answered with a failure of its own. */
    SERVICE_ERROR,

    /** An answer that could not be read as a product card. */
    MALFORMED_RESPONSE,
}
