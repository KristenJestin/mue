package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.RecipeType
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY_SLOTS: LocalDate = LocalDate.parse("2026-08-19")

/**
 * The six windows, read **half-open**, and the one of them that crosses midnight.
 *
 * PRD_FOOD 10.3's table writes them `05:00 – 10:00`, but the sentence beneath it and PRD_FOOD 22
 * both say "une pomme a dix heures tombe en collation, un dessert a quatorze heures au dejeuner".
 * Read closed the two statements contradict each other; read half-open they agree, which is the
 * reading `MealSlot.forTime` settled in the domain contract and which the six moments keep.
 */
class MealSlotRulesWindowTest {

    @Test
    fun `breakfast opens at five o'clock sharp`() {
        assertEquals(MealSlot.BREAKFAST, MealSlotRules.slotFor(LocalTime.of(5, 0)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(LocalTime.of(4, 59)))
    }

    @Test
    fun `PRD_FOOD 22 - an apple at ten o'clock falls in the morning snack`() {
        assertEquals(MealSlot.BREAKFAST, MealSlotRules.slotFor(LocalTime.of(9, 59)))
        assertEquals(MealSlot.MORNING_SNACK, MealSlotRules.slotFor(LocalTime.of(10, 0)))
    }

    @Test
    fun `lunch opens at noon`() {
        assertEquals(MealSlot.MORNING_SNACK, MealSlotRules.slotFor(LocalTime.of(11, 59)))
        assertEquals(MealSlot.LUNCH, MealSlotRules.slotFor(LocalTime.NOON))
    }

    @Test
    fun `PRD_FOOD 22 - a dessert at two in the afternoon falls in lunch`() {
        assertEquals(MealSlot.LUNCH, MealSlotRules.slotFor(LocalTime.of(14, 0)))
    }

    @Test
    fun `lunch closes at half past two, exclusively`() {
        assertEquals(MealSlot.LUNCH, MealSlotRules.slotFor(LocalTime.of(14, 29, 59)))
        assertEquals(MealSlot.SNACK, MealSlotRules.slotFor(LocalTime.of(14, 30)))
    }

    @Test
    fun `dinner runs from half past six to ten, exclusively`() {
        assertEquals(MealSlot.SNACK, MealSlotRules.slotFor(LocalTime.of(18, 29)))
        assertEquals(MealSlot.DINNER, MealSlotRules.slotFor(LocalTime.of(18, 30)))
        assertEquals(MealSlot.DINNER, MealSlotRules.slotFor(LocalTime.of(21, 59)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(LocalTime.of(22, 0)))
    }

    @Test
    fun `the middle of the night is an evening snack, and no hour is without a moment`() {
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(LocalTime.MIDNIGHT))
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(LocalTime.of(23, 59)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(LocalTime.of(1, 0)))
    }

    @Test
    fun `every one of the six windows knows its own bounds`() {
        assertEquals(
            MealSlotWindow(LocalTime.of(5, 0), LocalTime.of(10, 0)),
            MealSlotRules.windowOf(MealSlot.BREAKFAST),
        )
        assertEquals(
            MealSlotWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)),
            MealSlotRules.windowOf(MealSlot.MORNING_SNACK),
        )
        assertEquals(
            MealSlotWindow(LocalTime.of(12, 0), LocalTime.of(14, 30)),
            MealSlotRules.windowOf(MealSlot.LUNCH),
        )
        assertEquals(
            MealSlotWindow(LocalTime.of(14, 30), LocalTime.of(18, 30)),
            MealSlotRules.windowOf(MealSlot.SNACK),
        )
        assertEquals(
            MealSlotWindow(LocalTime.of(18, 30), LocalTime.of(22, 0)),
            MealSlotRules.windowOf(MealSlot.DINNER),
        )
        assertEquals(
            MealSlotWindow(LocalTime.of(22, 0), LocalTime.of(5, 0)),
            MealSlotRules.windowOf(MealSlot.EVENING_SNACK),
        )
    }

    @Test
    fun `every moment now has a window, because none of them is merely everything else`() {
        MealSlot.entries.forEach { slot ->
            val window = MealSlotRules.windowOf(slot)
            assertTrue(slot.defaultTime in window, "$slot does not contain its own default time")
        }
    }

    @Test
    fun `a window is closed at its start and open at its end`() {
        val breakfast = MealSlotRules.windowOf(MealSlot.BREAKFAST)
        assertTrue(LocalTime.of(5, 0) in breakfast)
        assertTrue(LocalTime.of(9, 59, 59) in breakfast)
        assertFalse(LocalTime.of(10, 0) in breakfast)
        assertFalse(LocalTime.of(4, 59, 59) in breakfast)
    }

    @Test
    fun `only the window that crosses midnight reads as a union of its two halves`() {
        val evening = MealSlotRules.windowOf(MealSlot.EVENING_SNACK)
        assertTrue(evening.wrapsMidnight)
        assertTrue(LocalTime.of(22, 0) in evening)
        assertTrue(LocalTime.of(23, 59) in evening)
        assertTrue(LocalTime.MIDNIGHT in evening)
        assertTrue(LocalTime.of(4, 59) in evening)
        assertFalse(LocalTime.of(5, 0) in evening)
        assertFalse(LocalTime.of(21, 59) in evening)

        assertEquals(
            listOf(MealSlot.EVENING_SNACK),
            MealSlot.entries.filter { MealSlotRules.windowOf(it).wrapsMidnight },
        )
    }

    @Test
    fun `the window predicate and the preselection are two readings of one partition`() {
        var minute = 0
        while (minute < 24 * 60) {
            val time = LocalTime.of(minute / 60, minute % 60)
            val chosen = MealSlotRules.slotFor(time)
            MealSlot.entries.forEach { slot ->
                assertEquals(
                    slot == chosen,
                    MealSlotRules.isWithinWindow(slot, time),
                    "$time and $slot disagree",
                )
            }
            minute += 7
        }
    }

    @Test
    fun `the windows constrain nothing - any line may be logged in any moment`() {
        val breakfastAtNight = logEntryOf(at = "23:30", slot = MealSlot.BREAKFAST)
        assertEquals(MealSlot.BREAKFAST, breakfastAtNight.slot)
        assertEquals(MealSlot.EVENING_SNACK, MealSlotRules.slotFor(breakfastAtNight.consumedAt))
    }
}

/** PRD_FOOD 10.3: the default time of a new line, and the minute it is stored to. */
class MealSlotRulesDefaultTimeTest {

    @Test
    fun `today keeps the current time`() {
        val now = LocalTime.of(15, 42)
        assertEquals(now, MealSlotRules.defaultTime(MealSlot.SNACK, TODAY_SLOTS, TODAY_SLOTS, now))
    }

    @Test
    fun `a retroactive entry takes an hour of its moment, never the current time`() {
        val now = LocalTime.of(22, 10)
        val yesterday = TODAY_SLOTS.minusDays(1)
        assertEquals(
            LocalTime.of(8, 0),
            MealSlotRules.defaultTime(MealSlot.BREAKFAST, yesterday, TODAY_SLOTS, now),
        )
    }

    @Test
    fun `every moment has a default hour of its own, and no two share one`() {
        val past = TODAY_SLOTS.minusDays(3)
        val now = LocalTime.of(11, 0)
        assertEquals(LocalTime.of(8, 0), MealSlotRules.defaultTime(MealSlot.BREAKFAST, past, TODAY_SLOTS, now))
        assertEquals(LocalTime.of(11, 0), MealSlotRules.defaultTime(MealSlot.MORNING_SNACK, past, TODAY_SLOTS, now))
        assertEquals(LocalTime.of(13, 0), MealSlotRules.defaultTime(MealSlot.LUNCH, past, TODAY_SLOTS, now))
        assertEquals(LocalTime.of(16, 30), MealSlotRules.defaultTime(MealSlot.SNACK, past, TODAY_SLOTS, now))
        assertEquals(LocalTime.of(20, 0), MealSlotRules.defaultTime(MealSlot.DINNER, past, TODAY_SLOTS, now))
        assertEquals(LocalTime.of(23, 0), MealSlotRules.defaultTime(MealSlot.EVENING_SNACK, past, TODAY_SLOTS, now))
        assertEquals(MealSlot.entries.size, MealSlot.entries.map { it.defaultTime }.toSet().size)
    }

    @Test
    fun `every default time falls inside the window of its own moment`() {
        MealSlot.ORDERED.forEach { slot ->
            assertTrue(
                MealSlotRules.isWithinWindow(slot, slot.defaultTime),
                "${slot.defaultTime} is not in $slot",
            )
        }
    }

    @Test
    fun `today's default is brought to the minute`() {
        val now = LocalTime.of(15, 42, 51, 123)
        assertEquals(
            LocalTime.of(15, 42),
            MealSlotRules.defaultTime(MealSlot.SNACK, TODAY_SLOTS, TODAY_SLOTS, now),
        )
    }

    @Test
    fun `a stray second never decides which of two lines comes first`() {
        assertEquals(LocalTime.of(13, 0), MealSlotRules.normalize(LocalTime.of(13, 0, 59, 999)))
    }

    @Test
    fun `a future date is treated as a retroactive one, and never crashes`() {
        val tomorrow = TODAY_SLOTS.plusDays(1)
        assertEquals(
            LocalTime.of(13, 0),
            MealSlotRules.defaultTime(MealSlot.LUNCH, tomorrow, TODAY_SLOTS, LocalTime.NOON),
        )
    }
}

/** PRD_FOOD 10.1: how the lines of a day arrange themselves under the six moments. */
class MealSlotRulesGroupingTest {

    private val day = listOf(
        logEntryOf(at = "20:30", slot = MealSlot.DINNER, id = "dinner-late"),
        logEntryOf(at = "08:10", slot = MealSlot.BREAKFAST, id = "breakfast"),
        logEntryOf(at = "18:05", slot = MealSlot.DINNER, id = "dinner-early"),
        logEntryOf(at = "13:00", slot = MealSlot.LUNCH, id = "lunch"),
    )

    @Test
    fun `the lines of a moment come back ordered by time`() {
        val dinner = MealSlotRules.entriesIn(day, MealSlot.DINNER)
        assertEquals(listOf("dinner-early", "dinner-late"), dinner.map { it.id.value })
    }

    @Test
    fun `the six moments always appear, in their own order, filled or not`() {
        val grouped = MealSlotRules.groupBySlot(day)
        assertEquals(MealSlot.ORDERED, grouped.keys.toList())
        assertTrue(grouped.getValue(MealSlot.SNACK).isEmpty())
        assertEquals(2, grouped.getValue(MealSlot.DINNER).size)
    }

    @Test
    fun `an empty day is still six moments`() {
        val grouped = MealSlotRules.groupBySlot(emptyList())
        assertEquals(MealSlot.entries.size, grouped.size)
        assertTrue(grouped.values.all { it.isEmpty() })
    }

    @Test
    fun `sorting by time is stable, so two lines of the same minute keep their order`() {
        val sameMinute = listOf(
            logEntryOf(at = "08:00", id = "first"),
            logEntryOf(at = "08:00", id = "second"),
        )
        assertEquals(listOf("first", "second"), MealSlotRules.sortedByTime(sameMinute).map { it.id.value })
    }

    @Test
    fun `only the lines of the selected day take part`() {
        val across = day + logEntryOf(isoDate = "2026-08-18", at = "13:00", id = "yesterday")
        assertEquals(4, MealSlotRules.entriesOn(across, TODAY_SLOTS).size)
        assertEquals(1, MealSlotRules.entriesOn(across, TODAY_SLOTS.minusDays(1)).size)
    }

    @Test
    fun `a day nobody wrote on has no line at all`() {
        assertTrue(MealSlotRules.entriesOn(day, TODAY_SLOTS.minusDays(10)).isEmpty())
    }

    @Test
    fun `three lines of different natures share one moment without replacing each other`() {
        val breakfast = listOf(
            logEntryOf(at = "08:00", slot = MealSlot.BREAKFAST, title = "Yoghurt", id = "a"),
            logEntryOf(at = "08:05", slot = MealSlot.BREAKFAST, title = "Banana", id = "b"),
        )
        assertEquals(2, MealSlotRules.entriesIn(breakfast, MealSlot.BREAKFAST).size)
    }
}

/** PRD_FOOD 8.5 and 12: a moment holds at most one proposal, and none of them enters a total. */
class MealSlotRulesPlanTest {

    private val plans = listOf(
        planOf(slot = MealSlot.LUNCH, recipeId = "recipe-curry"),
        planOf(slot = MealSlot.DINNER, recipeId = "recipe-soup"),
    )

    @Test
    fun `a moment finds its own proposal`() {
        assertEquals("recipe-curry", MealSlotRules.planIn(plans, MealSlot.LUNCH)?.recipeId?.value)
        assertEquals("recipe-soup", MealSlotRules.planIn(plans, MealSlot.DINNER)?.recipeId?.value)
    }

    @Test
    fun `a moment with no proposal says so with a null rather than an empty card`() {
        assertNull(MealSlotRules.planIn(plans, MealSlot.BREAKFAST))
    }

    @Test
    fun `two proposals for one moment resolve to the last, never to both`() {
        val duplicated = plans + planOf(slot = MealSlot.LUNCH, recipeId = "recipe-replacement")
        assertEquals(
            "recipe-replacement",
            MealSlotRules.planIn(duplicated, MealSlot.LUNCH)?.recipeId?.value,
        )
    }

    @Test
    fun `a confirmed proposal stops being shown`() {
        val confirmed = plans + planOf(
            slot = MealSlot.BREAKFAST,
            recipeId = "recipe-porridge",
            consumedLogEntryId = "entry-1",
        )
        val pending = MealSlotRules.pendingPlans(confirmed)
        assertEquals(2, pending.size)
        assertTrue(pending.none { it.slot == MealSlot.BREAKFAST })
    }

    @Test
    fun `deleting the line puts the proposal back in waiting`() {
        val confirmed = planOf(slot = MealSlot.LUNCH, consumedLogEntryId = "entry-1")
        assertTrue(confirmed.isConsumed)
        val released = confirmed.copy(consumedLogEntryId = null)
        assertFalse(released.isConsumed)
        assertEquals(1, MealSlotRules.pendingPlans(listOf(released)).size)
    }

    @Test
    fun `the day screen sees one proposal per moment, in the moments' own order`() {
        val bySlot = MealSlotRules.plansBySlot(plans)
        assertEquals(MealSlot.ORDERED, bySlot.keys.toList())
        assertNull(bySlot.getValue(MealSlot.BREAKFAST))
        assertNull(bySlot.getValue(MealSlot.SNACK))
        assertNotNull(bySlot.getValue(MealSlot.LUNCH))
    }

    @Test
    fun `a proposal carries no nutritional value and enters no total before it is confirmed`() {
        val proposal = planOf(slot = MealSlot.LUNCH)
        val lines = MealSlotRules.entriesIn(emptyList(), MealSlot.LUNCH)
        assertEquals(Nutrients.ZERO, NutritionMath.total(lines))
        assertEquals(MealSlot.LUNCH, proposal.slot)
    }
}

/**
 * Which moment a **proposal** opens on, when there is no clock to deduce one from.
 *
 * This is the one question planning asks that the journal never has to. PRD_FOOD 8.5 gives
 * `MealPlanEntry` a moment and no time at all, so `slotFor` — the whole of FR-FOOD-007 — has
 * nothing to read: a meal planned for next Thursday was not eaten at any hour, and the hour it
 * was *planned at* is a fact about the planner.
 *
 * The two things that do exist decide it instead: the dish's own `RecipeType` (PRD_FOOD 11 puts
 * one on every recipe, and PRD_FOOD 8.5 makes a proposal always reference one), and the moments
 * the day already holds (PRD_FOOD 8.5 again: at most one proposal each).
 */
class MealSlotRulesPlanningTest {

    @Test
    fun `a breakfast recipe opens on breakfast`() {
        assertEquals(MealSlot.BREAKFAST, MealSlotRules.plannedSlotFor(RecipeType.BREAKFAST))
    }

    /**
     * PRD_FOOD 8.3: lunch and dinner are the same dish at two hours, so `MAIN` maps to a pair and
     * not to one moment. The earlier of the two is the default, because a day is planned forwards.
     */
    @Test
    fun `a main opens on lunch, and on dinner once lunch is spoken for`() {
        assertEquals(MealSlot.LUNCH, MealSlotRules.plannedSlotFor(RecipeType.MAIN))
        assertEquals(
            MealSlot.DINNER,
            MealSlotRules.plannedSlotFor(RecipeType.MAIN, taken = setOf(MealSlot.LUNCH)),
        )
    }

    @Test
    fun `a snack opens on the afternoon snack, then on the two others in turn`() {
        assertEquals(MealSlot.SNACK, MealSlotRules.plannedSlotFor(RecipeType.SNACK))
        assertEquals(
            MealSlot.MORNING_SNACK,
            MealSlotRules.plannedSlotFor(RecipeType.SNACK, taken = setOf(MealSlot.SNACK)),
        )
        assertEquals(
            MealSlot.EVENING_SNACK,
            MealSlotRules.plannedSlotFor(
                RecipeType.SNACK,
                taken = setOf(MealSlot.SNACK, MealSlot.MORNING_SNACK),
            ),
        )
    }

    /**
     * A default never falls outside the family the recipe's type names.
     *
     * A breakfast recipe whose two moments are both taken opens on breakfast again — and asks to
     * replace it (FR-PLAN-001) — rather than sliding into dinner. A wrong default near the truth
     * costs one tap; one that is nowhere near it is simply confusing.
     */
    @Test
    fun `a type whose moments are all taken falls back to its own first, never to another family`() {
        val everyBreakfastMoment = setOf(MealSlot.BREAKFAST, MealSlot.MORNING_SNACK)

        assertEquals(
            MealSlot.BREAKFAST,
            MealSlotRules.plannedSlotFor(RecipeType.BREAKFAST, taken = everyBreakfastMoment),
        )
    }

    /** Every type offers at least one moment, so the default can never be absent. */
    @Test
    fun `each recipe type names at least one moment`() {
        RecipeType.entries.forEach { type ->
            assertTrue(MealSlotRules.plannableSlotsFor(type).isNotEmpty(), type.id)
        }
    }

    /**
     * PRD_FOOD 8.5: a **confirmed** proposal still occupies its moment.
     *
     * The row is keyed `(date, moment)` whether or not `I ate this` has been pressed, so a
     * planning sheet that read only the pending ones would call a moment free and then silently
     * overwrite a confirmed row — and with it the link to the journal line it created.
     */
    @Test
    fun `a confirmed proposal still holds its moment`() {
        val confirmedLunch = listOf(planOf(slot = MealSlot.LUNCH, consumedLogEntryId = "entry-1"))

        // `pendingPlans` would call this day free; the row exists all the same.
        assertTrue(MealSlotRules.pendingPlans(confirmedLunch).isEmpty())
        assertEquals(setOf(MealSlot.LUNCH), MealSlotRules.plannedSlots(confirmedLunch))
        assertEquals(
            MealSlot.DINNER,
            MealSlotRules.plannedSlotFor(
                RecipeType.MAIN,
                MealSlotRules.plannedSlots(confirmedLunch),
            ),
        )
    }

    @Test
    fun `a day with no proposal holds no moment`() {
        assertTrue(MealSlotRules.plannedSlots(emptyList()).isEmpty())
    }
}
