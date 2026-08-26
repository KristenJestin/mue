package fr.kristenjestin.mue.ui.food

import fr.kristenjestin.mue.ui.food.add.FoodAddMessages
import fr.kristenjestin.mue.ui.food.day.FoodDayMessages
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turns of phrase that claim the food has already been eaten.
 *
 * Each of the five was in shipped copy, and each of them was wrong on the same day the owner
 * pressed the same buttons: `Add what you ate` on a dinner not yet cooked, `What did you eat?` in
 * a sheet opened at six in the evening for eight o'clock, `Enter how much you had` under an empty
 * weight field, `What was it?` over a restaurant meal being written down in advance,
 * `Servings eaten` on a portion still in the pot.
 *
 * They are matched as phrases rather than as words on purpose. `ate`, `was` and `logged` all
 * appear in copy that is *correctly* past — a proposal that was confirmed, a line that was saved,
 * a journal that holds nothing yet — and a guard that caught those would be a guard nobody could
 * leave switched on.
 */
private val PAST_TENSE_CLAIMS = listOf(
    "you ate",
    "did you eat",
    "you had",
    "was it",
    "eaten",
    "you were",
)

/**
 * The module's copy, read for one assumption: that the meal is already over.
 *
 * The owner's first finding, in his words:
 *
 *   *"« Add what you ate » alors que c'est parfois sur des horaires qui ne sont pas encore
 *   passés ?"*
 *
 * He is right about more than the button he named. A moment later today has not happened yet and
 * its `+` is pressed all the same — that is the ordinary case, not the exception — and five
 * separate strings across the two message files told that reader they were in the wrong place.
 *
 * This walks **every** string constant of both objects rather than the five that were wrong, which
 * is the difference between fixing a finding and closing it: the next past tense written into
 * either file fails here before it reaches a phone.
 */
class FoodCopyTest {

    @Test
    fun `no string on the day screen assumes the meal has already happened`() {
        assertNoPastTense(stringsOf(FoodDayMessages))
    }

    @Test
    fun `no string in the add sheet assumes the meal has already happened`() {
        assertNoPastTense(stringsOf(FoodAddMessages))
    }

    /**
     * The two strings that are past tense on purpose, pinned so the guard above cannot be
     * "fixed" by rewriting them.
     *
     * `I ate this` is PRD_FOOD 12's own name for the action, and it is a claim about the past by
     * definition: it turns a proposal into a line saying the meal happened. The rescaling note is
     * a fact about a line that was already saved (PRD_FOOD 8.4), not about a meal that was already
     * eaten. Neither is what the finding is about, and neither may drift into it.
     */
    @Test
    fun `the past tense that is meant stays exactly as it is`() {
        assertEquals("I ate this", FoodDayMessages.I_ATE_THIS)
        assertEquals(
            "Rescaled from what this entry was saved with",
            FoodAddMessages.SERVINGS_FROZEN,
        )
    }

    /**
     * The invitation and its neighbour are one pair.
     *
     * A moment's first add and its next one are read one under the other as a day fills up, and
     * `Add what you ate` beside `Add something else` was already two voices before it was wrong
     * about the tense.
     */
    @Test
    fun `the two add labels are written as one pair`() {
        assertEquals("Add something", FoodDayMessages.ADD_FIRST)
        assertEquals("Add something else", FoodDayMessages.ADD_MORE)
    }

    private fun assertNoPastTense(strings: Map<String, String>) {
        val offenders = strings.filterValues { value ->
            PAST_TENSE_CLAIMS.any { claim -> value.contains(claim, ignoreCase = true) }
        }

        assertTrue(
            offenders.isEmpty(),
            "these strings assume the meal is already over: " +
                offenders.entries.joinToString { "${it.key} = «${it.value}»" },
        )
    }

    /**
     * Every `String` constant of a message object, by name.
     *
     * Java reflection rather than `kotlin-reflect`, which the app does not depend on. A Kotlin
     * `object`'s `const val` compiles to a static field on its class, so the receiver is ignored
     * for those and used for the rest; private constants are read too, because a private string is
     * still a string that reaches the glass through the function that assembles it.
     */
    private fun stringsOf(messages: Any): Map<String, String> =
        messages.javaClass.declaredFields
            .filter { it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(messages) as String)
            }
}
