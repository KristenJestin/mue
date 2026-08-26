package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.RecipeId
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The proposals (PRD_FOOD 12).
 *
 * Every method addresses a proposal by its [MealPlanKey] and none by a generated id, because
 * `(date, slot)` **is** the identity (PRD_FOOD 8.5 and 21.3): a slot holds at most one proposal,
 * the uniqueness is a unique index in SQLite (PRD_FOOD 20.2), and two devices proposing for the
 * same slot therefore address the same row instead of producing a pair to reconcile.
 */
interface MealPlanRepository {

    /** The proposals of one day — at most four, one per slot. */
    fun observeDay(date: LocalDate): Flow<List<MealPlanEntry>>

    /** The proposals inside [window], for the range PRD_FOOD 21.5 lets `list_meal_plan` read. */
    fun observeIn(window: DateWindow): Flow<List<MealPlanEntry>>

    suspend fun find(key: MealPlanKey): MealPlanEntry?

    /**
     * Poses a proposal, **replacing** whatever occupied the slot (PRD_FOOD FR-PLAN-001).
     *
     * The confirmation the interface asks for before overwriting is a screen's business, not
     * this contract's: by the time it is called the decision is made, and the write has to be
     * the same single upsert whether it came from a person or from an MCP client.
     */
    suspend fun save(entry: MealPlanEntry)

    /**
     * Links a proposal to the journal line `I ate this` created, or unlinks it with a null id
     * when that line is deleted and the proposal goes back to waiting (PRD_FOOD FR-PLAN-003).
     */
    suspend fun setConsumed(key: MealPlanKey, logEntryId: FoodLogEntryId?)

    /** `Dismiss`: frees the slot and touches neither the recipe nor the journal (PRD_FOOD 12). */
    suspend fun delete(key: MealPlanKey)

    /**
     * PRD_FOOD 11: deleting a recipe releases the proposals that referenced it. Returns the slots
     * that were freed so the interface can name them.
     */
    suspend fun deleteReferencing(recipe: RecipeId): List<MealPlanKey>
}
