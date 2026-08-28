package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat

/**
 * How the catalogue words what [FoodLabels] has already rendered (PRD_FOOD 13.2 and 18).
 *
 * Nothing here formats a number, exactly as nothing in `FoodDayFormat` does: every energy and
 * every macronutrient arrives from [FoodLabels], the one place that knows an unknown value is
 * `—` and never `0`. This object only decides which of those strings sit beside which, and which
 * noun follows them.
 *
 * [FoodDayFormat.spoken] and [FoodDayFormat.sentence] are reused rather than copied. `—` is a
 * drawing and `≈` is a symbol; both have to be turned into words for PRD_FOOD 18, and a second
 * definition of that translation would be a second chance to let an unknown be heard as a zero.
 */
object FoodCatalogueFormat {

    /** The four macronutrients of PRD_FOOD 8.2, in the order that section lists them. */
    val MACRO_NOUNS: List<String> = listOf("protein", "carbs", "fat", "fibre")

    /** `≈ 59 kcal`, or `—`. The distinction is [FoodLabels]', and nothing here collapses it. */
    fun energy(nutrients: Nutrients): String = FoodLabels.energy(nutrients.energy)

    /**
     * The four macronutrients with their nouns — `≈ 10.0 g protein`, `— fibre`.
     *
     * The unknown ones are kept rather than dropped. A card that simply omitted fibre would say
     * the same thing as a card that has no fibre row at all, and PRD_FOOD 13.2 exists to keep
     * "nobody wrote it down" apart from "there is none".
     */
    fun macros(nutrients: Nutrients): List<String> =
        FoodLabels.macros(nutrients).mapIndexed { index, value -> "$value ${MACRO_NOUNS[index]}" }
}

/**
 * One row of the catalogue (PRD_FOOD 9.4 and FR-CATALOG-004).
 *
 * Every string it carries is final: the screen picks a colour and a position and never a value.
 * That is what makes PRD_FOOD 13.2's rule — unknown is `—`, never `0` — provable on the JVM
 * rather than only in a screenshot.
 *
 * [figures] is **empty** when the person has turned `Show energy` off (PRD_FOOD 13.2 and
 * FR-FOOD-010). Empty rather than blanked out: a row of dashes would be indistinguishable from
 * a food nobody has filled in, which is the very confusion this module spends its effort on.
 */
@Immutable
data class FoodRowUiState(
    val id: FoodId,
    val name: String,
    val brand: String?,
    val source: FoodSource,
    val sourceLabel: String,
    val iconName: String,
    val isReadOnly: Boolean,
    /** `per 100 g` or `per 100 ml`, the basis every figure below is quoted against. */
    val basisLabel: String,
    /** The energy and the four macronutrients, or nothing at all when energy is hidden. */
    val figures: List<String>,
    /** PRD_FOOD 18: the whole row as one announcement, values with their units. */
    val description: String,
) {

    val hasFigures: Boolean get() = figures.isNotEmpty()

    /**
     * The one line under the name: the prototype's `(brand||source)`.
     *
     * Kept as `brand · source` where the prototype chooses between the two, because
     * FR-CATALOG-004 asks every food to say where it came from and a branded product printing
     * only `Demo brand` would have stopped saying it was scanned.
     */
    val metaLabel: String get() = listOfNotNull(brand, sourceLabel).joinToString(META_SEPARATOR)

    companion object {

        fun of(food: Food, showEnergy: Boolean = true): FoodRowUiState {
            val sourceLabel = FoodCatalogueMessages.sourceLabel(food.source)
            val basis = FoodCatalogueMessages.per100(food.referenceUnit)
            val figures = if (showEnergy) {
                listOf(FoodCatalogueFormat.energy(food.per100)) +
                    FoodCatalogueFormat.macros(food.per100)
            } else {
                emptyList()
            }

            return FoodRowUiState(
                id = food.id,
                name = food.name,
                brand = food.brand,
                source = food.source,
                sourceLabel = sourceLabel,
                iconName = FoodIcons.forSource(food.source),
                isReadOnly = food.isReadOnly,
                basisLabel = basis,
                figures = figures,
                description = FoodDayFormat.sentence(
                    food.name,
                    food.brand,
                    sourceLabel,
                    figures.takeIf { it.isNotEmpty() }?.let { values ->
                        "$basis: " + values.joinToString(FoodDayFormat.SEPARATOR) {
                            FoodDayFormat.spoken(it)
                        }
                    },
                ),
            )
        }
    }
}

/** The prototype's `·` between a brand and its provenance. */
private const val META_SEPARATOR: String = " · "

/**
 * The `Foods` view (PRD_FOOD 7 and 9.4): one search bar, one source filter, one list.
 *
 * [recent] and [results] are two lists rather than one because PRD_FOOD 9.4 gives them two
 * different jobs — "les aliments récemment utilisés apparaissent en tête **lorsque la recherche
 * est vide**" — and merging them would either lose the heading or duplicate a food that is both
 * recent and in the first page of the catalogue.
 *
 * [resultLimit] is carried rather than hidden because the screen has to be able to say it. The
 * embedded subset holds 1 038 entries; the list asks the database for [resultLimit] of them and
 * says so when it gets that many, so nobody concludes their food is missing from a page that was
 * quietly cut short.
 */
@Immutable
data class FoodsUiState(
    val query: String = "",
    /** PRD_FOOD 9.4's one filter. `null` is every source at once, which is the default. */
    val source: FoodSource? = null,
    val isLoading: Boolean = true,
    val recent: List<FoodRowUiState> = emptyList(),
    /**
     * The catalogue itself, with anything already shown under [recent] left out.
     *
     * A food that was eaten yesterday is also in the catalogue, and drawing it twice in one
     * scroll would be two cards for one food — and two nodes answering to one test tag.
     */
    val results: List<FoodRowUiState> = emptyList(),
    val resultLimit: Int = FoodCatalogueViewModel.RESULT_LIMIT,
    /**
     * How many rows the database returned, before [recent] was subtracted.
     *
     * It is what says whether the page is full: de-duplicating shortens the list without making
     * the catalogue any smaller, and reading the cap off the drawn rows would quietly stop
     * warning about it.
     */
    val matchCount: Int = results.size,
    /** PRD_FOOD 13.2 and FR-FOOD-010, read from the preferences and never from a control here. */
    val showEnergy: Boolean = true,
) {

    val isSearching: Boolean get() = query.isNotBlank()

    val hasRecent: Boolean get() = recent.isNotEmpty()

    val hasResults: Boolean get() = results.isNotEmpty()

    /** True when the database had at least as many rows as the list asked for. */
    val isCapped: Boolean get() = matchCount >= resultLimit

    /**
     * What an empty list says, or null while it has something to show.
     *
     * PRD_FOOD 17 gives the searching case its own answer — the creation is offered, prefilled
     * with what was typed — so the sentence names the term. A filter that empties the catalogue
     * is a different fact and gets a different sentence; the catalogue itself is never empty,
     * PRD_FOOD 9.1 having seeded it before the first launch.
     */
    val emptyMessage: String?
        get() = when {
            isLoading || hasResults -> null
            isSearching -> FoodCatalogueMessages.noMatch(query)
            source != null -> FoodCatalogueMessages.noMatchInSource(source)
            else -> null
        }

    /** PRD_FOOD 17 and 22: a search with no result offers a food already carrying the term. */
    val createLabel: String
        get() = if (isSearching) {
            FoodCatalogueMessages.createNamed(query)
        } else {
            FoodCatalogueMessages.CREATE_FOOD
        }

    /** What the creation prefills the name with, or null when nothing was typed. */
    val createPrefill: String? get() = query.trim().takeIf { it.isNotEmpty() }
}
