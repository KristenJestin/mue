package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The food journal (PRD_FOOD 10).
 *
 * A day holds as many lines as the person wants, of any of the three kinds, in any slot: there
 * is no uniqueness rule here of the sort a weight measurement has, and PRD_FOOD 21.3 makes that
 * explicit for synchronisation too — two lines created separately coexist and never merge.
 *
 * Ordering is fixed by the storage and is the same everywhere the app lists a day: by slot in
 * the order of `MealSlot.ORDERED`, then by time, then by a stable tiebreak so two identical
 * lines never swap places between two reads.
 *
 * No total is stored. PRD_FOOD 13.1 recomputes every slot and every day from the lines, which is
 * what lets a correction take effect immediately and what keeps the strict-null rule in one
 * place.
 */
interface FoodLogRepository {

    /** Every line of one calendar day, the `Day` screen of PRD_FOOD 10.1. */
    fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>>

    /** The lines inside [window], oldest first; what the seven days of PRD_FOOD 10.5 read. */
    fun observeIn(window: DateWindow): Flow<List<FoodLogEntry>>

    /**
     * The dates that carry at least one line, inside [window].
     *
     * PRD_FOOD 10.5 counts "le nombre de jours renseignés" and PRD_FOOD 10.4 forbids inventing a
     * day, so an empty day must be absent from this list rather than present with a zero.
     */
    fun observeLoggedDatesIn(window: DateWindow): Flow<List<LocalDate>>

    suspend fun findById(id: FoodLogEntryId): FoodLogEntry?

    /** The line a proposal was confirmed into, so deleting it can put the proposal back. */
    suspend fun findByPlan(key: MealPlanKey): FoodLogEntry?

    /**
     * The most recent lines that quoted a catalogue food, most recent first and one per food.
     * What PRD_FOOD 9.4 orders the empty search by, and what "the same again" reads.
     */
    suspend fun recentlyUsedFoods(limit: Int): List<FoodId>

    /** Creates or replaces one line. Its values are already frozen by the caller. */
    suspend fun save(entry: FoodLogEntry)

    /**
     * PRD_FOOD FR-FOOD-008 and 12: removing a line releases the proposal it confirmed, which
     * goes back to waiting rather than disappearing with it.
     */
    suspend fun delete(id: FoodLogEntryId)
}
