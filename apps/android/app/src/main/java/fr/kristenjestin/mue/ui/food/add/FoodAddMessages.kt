package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import java.util.Locale

/**
 * Every word the `Add food` sheet and the food picker put on screen (PRD_FOOD 7, 11, 15 and 18).
 *
 * Constants rather than resources, exactly as `FoodDayMessages` and `LogActivityMessages` are:
 * Mue ships in one language, and a string a test can name is a string a test cannot mistype.
 * The accessibility labels sit here too — PRD_FOOD 18 makes them part of the interface.
 *
 * **No validation message lives here.** PRD_FOOD 15's refusals are `FoodValidation`'s own
 * sentences, asserted character for character by its unit tests; restating one here would let the
 * screen and the rule drift apart. What this file adds is only what the domain has no opinion
 * about: the name of a control, the shape of an announcement, and the two words a save button
 * can carry.
 */
internal object FoodAddMessages {

    // region the sheet itself (PRD_FOOD 7)

    const val ADD_TITLE: String = "Add food"

    /** FR-FOOD-008 reuses this very sheet to correct a line that already exists. */
    const val EDIT_TITLE: String = "Edit entry"

    const val CLOSE: String = "Close"

    // endregion

    // region the ways in (PRD_FOOD 7, FR-FOOD-002 to 005)

    /**
     * Not "What did you eat?".
     *
     * A moment later today has not happened yet, and the sheet opens on it just the same: the `+`
     * of tonight's dinner is pressed at six o'clock as readily as at nine. The past tense told
     * that reader they were in the wrong place. This asks about the entry being written, which is
     * true whether the food is already eaten or is about to be — and it stays a question about the
     * food rather than becoming a vague one about "an item".
     */
    const val PATHS_EYEBROW: String = "What are you adding?"
    const val PATHS_TITLE: String = "Pick a way in."

    const val SEARCH_PATH: String = "Search a food"
    const val SEARCH_PATH_DESCRIPTION: String = "The catalogue, your products, your own foods"

    const val RECIPE_PATH: String = "Use a recipe"
    const val RECIPE_PATH_DESCRIPTION: String = "One of your saved preparations"

    const val QUICK_PATH: String = "Quick add"
    const val QUICK_PATH_DESCRIPTION: String = "A name and an energy, when that is all you know"

    /**
     * The way back to the three cards above, from whichever path was taken.
     *
     * Worded against [PATHS_TITLE] on purpose — `Pick a way in.` and `Choose another way` are the
     * same noun — so the control names the screen it returns to rather than describing a gesture.
     */
    const val CHANGE_PATH: String = "Choose another way"

    // endregion

    // region the food, and how much of it (FR-FOOD-006)

    const val CHANGE_FOOD: String = "Choose another food"
    const val AMOUNT_SECTION: String = "How much?"
    const val PORTIONS_LABEL: String = "Usual portions"
    const val FEWER_PORTIONS: String = "One portion fewer"
    const val MORE_PORTIONS: String = "One portion more"

    /** PRD_FOOD 8.6: the counter is an aid to typing, and the exact weight always wins. */
    const val PORTIONS_HINT: String = "Typing a weight takes over from the counter"

    const val WEIGHT_LABEL: String = "Weight"
    const val VOLUME_LABEL: String = "Volume"

    /** FR-FOOD-006: only a food carrying a cooking ratio is ever asked this. */
    const val COOKED_STATE_LABEL: String = "Weighed"

    const val PER_100_SECTION: String = "Per 100"
    const val CONTRIBUTION_SECTION: String = "In this entry"

    const val ENERGY_NOUN: String = "Energy"
    const val PROTEIN_NOUN: String = "Protein"
    const val CARBS_NOUN: String = "Carbohydrate"
    const val FAT_NOUN: String = "Fat"
    const val FIBRE_NOUN: String = "Fibre"

    // endregion

    // region the quick add (FR-FOOD-005)

    const val QUICK_SECTION: String = "Quick add"

    /**
     * Present tense, because the meal may not have happened.
     *
     * "What was it?" is a fine question about lunch three hours ago and a wrong one about the
     * restaurant dinner being written down at five in the afternoon. The present answers both,
     * and asks for exactly the same thing.
     */
    const val QUICK_NAME_LABEL: String = "What is it?"
    const val QUICK_ENERGY_LABEL: String = "Energy"
    const val QUICK_PROTEIN_LABEL: String = "Protein · optional"

    /** PRD_FOOD 8.4: a quick add is stored approximate, and says so before it is saved. */
    const val QUICK_APPROXIMATE: String = "Saved as an estimate"

    // endregion

    // region correcting a recipe line (FR-FOOD-008)

    const val SERVINGS_SECTION: String = "How many servings?"

    /**
     * The noun alone: the section above it already asks "how many", and the participle carried an
     * assumption the field does not need. A portion and a half is a portion and a half whether it
     * is already gone or is about to be served.
     */
    const val SERVINGS_LABEL: String = "Servings"

    /** PRD_FOOD 8.4: a recipe edited since is not a line rewritten. */
    const val SERVINGS_FROZEN: String = "Rescaled from what this entry was saved with"

    // endregion

    // region a line whose food is gone (PRD_FOOD 17)

    const val MISSING_FOOD: String = "This food is no longer in the catalogue"
    const val MISSING_FOOD_DETAIL: String =
        "Its values stay as they were saved. The moment and the time can still be changed."

    // endregion

    // region when and where (PRD_FOOD 10.3, FR-FOOD-007)

    const val SLOT_SECTION: String = "Which moment?"
    const val TIME_LABEL: String = "Time"
    const val CHANGE_TIME: String = "Change"
    const val TIME_SHEET_TITLE: String = "What time?"
    const val CLOSE_TIME_SHEET: String = "Close the time picker"
    const val USE_THIS_TIME: String = "Use this time"

    // endregion

    // region saving (FR-FOOD-008)

    const val SAVE_ENTRY: String = "Save entry"
    const val SAVE_CHANGES: String = "Save changes"
    const val DELETE_ENTRY: String = "Delete entry"
    const val ENTRY_DELETED: String = "Entry deleted"

    /** The promise the two shipped modules already make on a failed write: nothing was lost. */
    const val SAVE_FAILED: String = "Couldn't save. Your entry is still here."
    const val DELETE_FAILED: String = "Couldn't delete. Your entry is still here."
    const val TRY_AGAIN: String = "Try again"

    /**
     * PRD_FOOD 15: a quantity is required before a line can be written at all.
     *
     * `Quantity` is the domain's own noun — `FoodValidation.INGREDIENT_QUANTITY_ERROR` uses it —
     * so the two refusals a person can meet on this field speak the same word. "how much you had"
     * did not, and told a reader writing down tonight's dinner that they had already eaten it.
     */
    const val NO_QUANTITY: String = "Enter a quantity"

    // endregion

    // region the picker (PRD_FOOD 9.4 and 11)

    const val PICKER_TITLE: String = "Choose a food"
    const val SEARCH_PLACEHOLDER: String = "Rice, skyr, olive oil…"
    const val SEARCH_LABEL: String = "Search foods"
    const val CLEAR_SEARCH: String = "Clear the search"
    const val RECENT_SECTION: String = "Recently used"
    const val RESULTS_SECTION: String = "Results"
    const val SOURCE_FILTER_LABEL: String = "Where a food came from"
    const val SOURCE_ALL: String = "All"
    const val SOURCE_CIQUAL: String = "Catalogue"
    const val SOURCE_OPEN_FOOD_FACTS: String = "Packaged"
    const val SOURCE_CUSTOM: String = "Mine"

    /** PRD_FOOD 17: an empty search says what is true and offers the way out of it. */
    const val NOTHING_RECENT: String = "Nothing logged yet. Search the catalogue above."
    const val NO_RESULTS: String = "No food matches that."
    const val CREATE_FOOD: String = "Create a food"

    // endregion

    /**
     * The two words a food uses for its states, folded the way [FoodLabels.cookedSuffix] folds
     * the cooked one.
     *
     * [FoodLabels] owns the cooked half and nothing else; the raw half is the same fold through
     * [Locale.ROOT] — never the phone's locale, for the reason `Food.fold` gives: a Turkish
     * device lower-cases `I` to a dotless one, and a label must not change shape with the region.
     */
    fun stateWord(food: Food, cooked: Boolean): String =
        if (cooked) FoodLabels.cookedSuffix(food) else food.rawLabel.trim().lowercase(Locale.ROOT)

    /**
     * What the quantity field is called, **with the state the number is read in beside it**.
     *
     * This is the whole of a finding the planning pass paid for: 600 g of *cooked* rice typed
     * against a raw reference counts nearly three times the energy actually eaten, and a field
     * labelled only `Weight` says nothing about which of the two the person is holding. A food
     * that declares no cooked state carries no such ambiguity and keeps the plain noun.
     */
    fun quantityLabel(food: Food, cooked: Boolean): String {
        val noun = when (food.referenceUnit) {
            ReferenceUnit.GRAM -> WEIGHT_LABEL
            ReferenceUnit.MILLILITRE -> VOLUME_LABEL
        }
        if (!food.hasCookedState) return noun
        return "$noun, ${stateWord(food, cooked)}"
    }

    /** `Per 100 g raw` — what the figures under it are quoted against (PRD_FOOD 8.2). */
    fun per100Label(food: Food): String {
        val basis = "$PER_100_SECTION ${food.referenceUnit.symbol}"
        return if (food.hasCookedState) "$basis ${stateWord(food, cooked = false)}" else basis
    }

    /**
     * PRD_FOOD 13.1's first line said out loud: the weight the values are actually computed from.
     *
     * Shown only when a cooked weight was typed, because that is the only time the number on the
     * scale and the number behind the figures are two different numbers.
     */
    fun countedAs(referenceWeight: String, rawWord: String): String =
        "Counted as $referenceWeight $rawWord"

    /** What the save button announces it will do, moment and day included (PRD_FOOD 18). */
    fun saveDescription(label: String, slot: MealSlot, dateLabel: String): String =
        "$label, ${slot.label}, $dateLabel"

    /** PRD_FOOD 9.4 and FR-CATALOG-004: a result row says where its food came from. */
    fun sourceLabel(source: FoodSource): String = when (source) {
        FoodSource.CIQUAL -> SOURCE_CIQUAL
        FoodSource.OPEN_FOOD_FACTS -> SOURCE_OPEN_FOOD_FACTS
        FoodSource.CUSTOM -> SOURCE_CUSTOM
    }
}
