package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.ReferenceUnit

/**
 * Every word the catalogue puts on screen (PRD_FOOD 9, 13.2, 16.3, 17 and 18).
 *
 * Constants rather than resources, for the reason `FoodDayMessages` gives: the app ships in one
 * language, and a sentence a test can name is a sentence a test cannot mistype. The refusals of
 * PRD_FOOD 17 live here too — a refusal is copy the person reads, and it is the only thing that
 * turns `FoodDeletion.ReadOnly` from a branch of a sealed type into an answer someone can act on.
 *
 * The error messages of the food form are **absent on purpose**: every one of them is already a
 * constant on [fr.kristenjestin.mue.domain.logic.FoodValidation], which owns PRD_FOOD 15's table.
 * Repeating one here would be a second sentence for one rule, and the two would drift.
 */
object FoodCatalogueMessages {

    // region `Foods` (PRD_FOOD 9.4)

    const val EYEBROW: String = "Everything you can log"
    const val TITLE: String = "Foods"

    const val SEARCH_PLACEHOLDER: String = "Search chicken, yoghurt, oats"
    const val SEARCH_LABEL: String = "Search foods"
    const val CLEAR_SEARCH: String = "Clear the search"

    /** PRD_FOOD 9.4: "un filtre restreint a une source". [SOURCE_ALL] is the absence of one. */
    const val SOURCE_ALL: String = "All"

    /** PRD_FOOD 9.4: "les aliments recemment utilises apparaissent en tete". */
    const val RECENT_TITLE: String = "Recently used"

    const val RESULTS_TITLE: String = "Catalogue"

    const val CREATE_FOOD: String = "New food"

    /** PRD_FOOD 18: the list scrolls, so its length has to be sayable as well as visible. */
    fun resultCount(count: Int): String = if (count == 1) "$count food" else "$count foods"

    /**
     * PRD_FOOD 9.4 and 9.5: the catalogue holds over a thousand entries, and a screen that drew
     * them all would be neither quick nor readable. The list is capped, and the cap is stated
     * rather than hidden — a silent truncation is how someone concludes their food is missing.
     */
    fun showingFirst(limit: Int): String =
        "Showing the first $limit. Keep typing to narrow it down."

    /** PRD_FOOD 17: "recherche sans resultat, proposition de creer un aliment pre-rempli". */
    fun noMatch(query: String): String = "Nothing in the catalogue matches ${quoted(query.trim())}."

    fun createNamed(query: String): String = "Create ${quoted(query.trim())}"

    /** The catalogue is never truly empty — PRD_FOOD 9.1 seeds it — but a filter can empty it. */
    fun noMatchInSource(source: FoodSource): String = "Nothing under ${sourceLabel(source)} yet."

    // endregion

    // region provenance (PRD_FOOD 16.3, FR-CATALOG-004)

    /**
     * FR-CATALOG-004: "chaque aliment affiche sa source".
     *
     * The words name what the entry *is* rather than which database it was fetched from:
     * `Ciqual` and `Open Food Facts` are the names of two tables, and neither tells someone
     * holding a yoghurt pot which one their food came from. PRD_FOOD 19's own icon table words
     * them the same way — generic catalogue, packaged product, personal food.
     */
    fun sourceLabel(source: FoodSource): String = when (source) {
        FoodSource.CIQUAL -> "Generic"
        FoodSource.OPEN_FOOD_FACTS -> "Packaged"
        FoodSource.CUSTOM -> "Personal"
    }

    /** PRD_FOOD 9.1, said once where it is read rather than once per refusal. */
    const val READ_ONLY_NOTE: String =
        "Reference foods come with Mue and cannot be changed. Duplicate this one to get a copy " +
            "you own."

    /** PRD_FOOD 13.2: values are quoted per 100 of the unit the food is measured in. */
    fun per100(unit: ReferenceUnit): String = "per 100 ${unit.symbol}"

    fun unitLabel(unit: ReferenceUnit): String = when (unit) {
        ReferenceUnit.GRAM -> "Grams"
        ReferenceUnit.MILLILITRE -> "Millilitres"
    }

    // endregion

    // region deletion (PRD_FOOD 9.3, 16.3 and 17)

    const val DELETE: String = "Delete food"
    const val DELETE_TITLE: String = "Delete this food?"
    const val DELETE_CONFIRM: String = "Delete"
    const val DELETE_CANCEL: String = "Keep it"
    const val CLOSE_DELETION: String = "Close"

    /** PRD_FOOD 9.3: "la suppression n'affecte aucune ligne de journal deja enregistree". */
    fun deleteBody(name: String): String =
        "${quoted(name)} leaves the catalogue. Entries already in your journal keep the values " +
            "they were saved with."

    fun deleted(name: String): String = "${quoted(name)} was deleted."

    /**
     * PRD_FOOD 17: a stale id. The row was already gone — removed on another device, or from a
     * screen this one had not refreshed — so nothing was deleted and nothing was lost.
     */
    const val NOT_FOUND: String = "That food is no longer in the catalogue."

    /** PRD_FOOD 9.1 and 17: the one half of the catalogue that is not the person's to remove. */
    const val READ_ONLY_REFUSAL: String =
        "Reference foods come with Mue and cannot be deleted. Duplicate this one instead: the " +
            "copy is yours to change and to remove."

    /**
     * PRD_FOOD 17: "suppression refusee, recettes concernees nommees", which PRD_FOOD 22 makes
     * an acceptance criterion in its own right.
     *
     * The names are printed, never counted. "Used by 3 recipes" would leave the person to open
     * every recipe they own to find which three, and that is the whole reason
     * [fr.kristenjestin.mue.domain.repository.FoodDeletion.UsedByRecipes] carries the list at
     * all rather than a flag.
     */
    fun usedByRecipes(recipeNames: List<String>): String {
        val verb = if (recipeNames.size == 1) "recipe uses" else "recipes use"
        val target = if (recipeNames.size == 1) "that recipe" else "those recipes"
        return "${listed(recipeNames)} $verb this food. Remove it from $target first, then " +
            "delete it."
    }

    /** `A`, `A and B`, `A, B and C` — an English list, never a count. */
    fun listed(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> quoted(names[0])
        else -> names.dropLast(1).joinToString(", ", transform = ::quoted) +
            " and ${quoted(names.last())}"
    }

    // endregion

    // region `Food editor` (PRD_FOOD 9.3, FR-CATALOG-003)

    const val NEW_TITLE: String = "New food"
    const val EDIT_TITLE: String = "Edit food"
    const val REFERENCE_TITLE: String = "Reference food"
    const val BACK: String = "Back"

    const val SAVE: String = "Save food"
    const val DUPLICATE: String = "Duplicate"

    const val NAME_LABEL: String = "Name"
    const val NAME_PLACEHOLDER: String = "Greek yoghurt"
    const val BRAND_LABEL: String = "Brand"
    const val OPTIONAL_PLACEHOLDER: String = "Optional"
    const val BARCODE_LABEL: String = "Barcode"
    const val UNIT_LABEL: String = "Measured in"

    const val VALUES_TITLE: String = "Nutrition"
    const val ENERGY_LABEL: String = "Energy"
    const val PROTEIN_LABEL: String = "Protein"
    const val CARBS_LABEL: String = "Carbohydrate"
    const val FAT_LABEL: String = "Fat"
    const val FIBRE_LABEL: String = "Fibre"

    /**
     * PRD_FOOD 15: "un champ vide est enregistre `null`, jamais `0`", and PRD_FOOD 9.2 calls an
     * incomplete card the nominal case. The form says so *before* anyone leaves a field empty,
     * so a blank reads as a decision rather than as an omission.
     */
    const val VALUES_HINT: String =
        "Leave a value empty when you do not know it. Mue shows it as a dash and never as a zero."

    const val SERVING_TITLE: String = "Usual portion"
    const val SERVING_LABEL_LABEL: String = "Portion name"
    const val SERVING_LABEL_PLACEHOLDER: String = "pot, apple, handful"
    const val SERVING_SIZE_LABEL: String = "One portion weighs"

    /** PRD_FOOD 8.6: the portion is an aid to entry, never a second nutritional unit. */
    const val SERVING_HINT: String =
        "An aid to entry only. Quantities are still stored as a weight or a volume."

    const val ENERGY_UNIT_SUFFIX: String = "kcal"
    const val MACRO_UNIT_SUFFIX: String = "g"

    /** PRD_FOOD 9.2: a copied product keeps its provenance however much it is corrected. */
    const val KEEPS_SOURCE_NOTE: String =
        "Correcting these values keeps the product's own barcode and origin."

    const val SAVE_REFUSED: String =
        "This food could not be saved: reference foods cannot be changed."

    // endregion

    /*
     * PRD_FOOD 13.2's `Preferences` region left this file with the screen that read it.
     *
     * `Food preferences` is drawn by `Profile` now, and this object is documented as every word
     * the **catalogue** puts on screen — a dictionary a view no longer opens. The three strings
     * are `FoodPreferencesMessages`, beside the screen, word for word.
     */

    /** One kind of quotation mark for the whole module, so a test can name what it will read. */
    fun quoted(text: String): String = "“$text”"
}
