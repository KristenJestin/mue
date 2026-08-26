package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Servings

/**
 * Every word the `Day` screen puts on screen, in one place (PRD_FOOD 10, 12, 17 and 18).
 *
 * They are constants rather than resources for the reason the rest of the app is: Mue ships in
 * one language, and a string a test can name is a string a test cannot mistype. The
 * accessibility labels sit here too — PRD_FOOD 18 makes them part of the interface, not a
 * decoration a screen adds afterwards.
 */
object FoodDayMessages {

    // region date navigation (PRD_FOOD 10.1)

    const val PREVIOUS_DAY: String = "Previous day"
    const val NEXT_DAY: String = "Next day"

    /** The date itself is a control: tapping it opens the calendar. */
    const val CHOOSE_DAY: String = "Choose a day"
    const val DATE_SHEET_TITLE: String = "Which day?"
    const val USE_THIS_DAY: String = "Use this day"
    const val CLOSE_DATE_SHEET: String = "Close the calendar"

    // endregion

    // region the moments (PRD_FOOD 10.1 and 17)

    /**
     * PRD_FOOD 17: "aucune ligne aujourd'hui → quatre moments vides et leur bouton d'ajout".
     *
     * The empty state of a moment *is* its add button, and it is an invitation rather than a
     * report: nothing here says a moment is missing something, because a breakfast that did not
     * happen is not an error.
     *
     * **Not "Add what you ate".** That was the first thing the owner named: a moment later today
     * has not been eaten yet, and its `+` is pressed all the same — "alors que c'est parfois sur
     * des horaires qui ne sont pas encore passés". The past tense made the commonest control in
     * the module tell half its readers they were in the wrong place.
     *
     * `Add something` and [ADD_MORE]'s `Add something else` are one pair rather than two
     * sentences, and neither claims a tense. What is being added to is the heading directly above
     * the button — the moment names itself, so the button does not have to.
     */
    const val ADD_FIRST: String = "Add something"

    const val ADD_MORE: String = "Add something else"

    /** What tapping a line does (PRD_FOOD FR-FOOD-008 reuses the `Add food` sheet to correct one). */
    const val EDIT_ENTRY: String = "Edit this entry"

    // endregion

    // region proposals (PRD_FOOD 12 and 18)

    /**
     * PRD_FOOD 18: "aucune information n'est portée par la seule couleur : une proposition se
     * distingue aussi par le libellé `Suggested` et son contour en pointillés".
     */
    const val SUGGESTED: String = "Suggested"

    const val I_ATE_THIS: String = "I ate this"
    const val SWAP: String = "Swap"
    const val DISMISS: String = "Dismiss"

    /** PRD_FOOD 17: a proposal whose recipe is gone says so rather than showing a blank card. */
    const val MISSING_RECIPE: String = "Recipe no longer available"

    // endregion

    /** What a moment says it holds, for a screen reader that cannot count the cards. */
    fun entryCount(count: Int): String = when (count) {
        0 -> NOTHING_LOGGED
        1 -> "$count $ENTRY_NOUN"
        else -> "$count $ENTRY_NOUN_PLURAL"
    }

    /**
     * A proposal's portions — `1 serving`, `1.5 servings`.
     *
     * The digits come from [FoodLabels.servings], which trims the thousandths PRD_FOOD 8.6
     * stores them in; only the noun is decided here.
     */
    fun servings(value: Servings): String {
        val digits = FoodLabels.servings(value)
        val noun = if (value == Servings.ONE) SERVING_NOUN else SERVING_NOUN_PLURAL
        return "$digits $noun"
    }

    /** PRD_FOOD 10.4: a moment with nothing in it states the fact and asks for nothing. */
    const val NOTHING_LOGGED: String = "nothing logged"

    private const val ENTRY_NOUN = "entry"
    private const val ENTRY_NOUN_PLURAL = "entries"
    private const val SERVING_NOUN = "serving"
    private const val SERVING_NOUN_PLURAL = "servings"
}
