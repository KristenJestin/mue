package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.MealPlanDao
import fr.kristenjestin.mue.data.local.database.toDomainOrNull
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.repository.MealPlanRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The propositions of PRD_FOOD 12, over Room.
 *
 * Every method is keyed by `(date, moment)` and none takes an id, because the proposition has
 * none: 21.3 makes `(date, moment)` the business key, the table's primary key is that pair, and
 * `MealPlanKey.aggregateId` is the same pair as the string the outbox journals. Replacing an
 * occupied moment is therefore an ordinary write that lands on the row already there — the
 * confirmation PRD_FOOD 8.5 asks for is an interface decision taken before this is called, not a
 * second row this has to reconcile afterwards.
 */
class RoomMealPlanRepository(
    private val dao: MealPlanDao,
    private val outbox: SyncOutbox = SyncOutbox(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : MealPlanRepository {

    override fun observeDay(date: LocalDate): Flow<List<MealPlanEntry>> =
        dao.observeDay(date.toString())
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
            .flowOn(ioDispatcher)

    override fun observeIn(window: DateWindow): Flow<List<MealPlanEntry>> =
        dao.observeInWindow(window.start?.toString(), window.endInclusive?.toString())
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
            .flowOn(ioDispatcher)

    override suspend fun find(key: MealPlanKey): MealPlanEntry? = withContext(ioDispatcher) {
        dao.find(key.plannedOn.toString(), key.slot.id)?.toDomainOrNull()
    }

    override suspend fun save(entry: MealPlanEntry) = withContext(ioDispatcher) {
        val stamp = now()
        dao.upsertWithMutation(
            entity = entry.toEntity(createdAt = stamp, updatedAt = stamp),
            mutation = outbox.mealPlanUpsert(entry),
        )
    }

    /**
     * Confirming a proposition, or undoing that confirmation (PRD_FOOD 8.5). It journals the
     * whole aggregate, because 21.3 resolves a proposition by last accepted mutation and a
     * partial payload would give the server no way to know what the rest still is.
     *
     * A moment with no proposition is left alone: writing a row here would invent a plan the
     * user never made, and the caller already learns nothing changed by finding none.
     */
    override suspend fun setConsumed(key: MealPlanKey, logEntryId: FoodLogEntryId?) =
        withContext(ioDispatcher) {
            val existing = dao.find(key.plannedOn.toString(), key.slot.id)?.toDomainOrNull()
                ?: return@withContext
            val updated = existing.copy(consumedLogEntryId = logEntryId)

            dao.setConsumedWithMutation(
                date = key.plannedOn.toString(),
                slot = key.slot.id,
                logEntryId = logEntryId?.value,
                updatedAt = now(),
                mutation = outbox.mealPlanUpsert(updated),
            )
        }

    override suspend fun delete(key: MealPlanKey) = withContext(ioDispatcher) {
        dao.deleteWithMutation(
            date = key.plannedOn.toString(),
            slot = key.slot.id,
            mutation = outbox.mealPlanDelete(key),
        )
    }

    /**
     * The propositions that go when a recipe goes. Each is an aggregate of its own (21.2), so
     * each leaves its own tombstone; returning the keys is what lets the caller say which
     * moments emptied.
     */
    override suspend fun deleteReferencing(recipe: RecipeId): List<MealPlanKey> =
        withContext(ioDispatcher) {
            val affected = dao.findReferencing(recipe.value).mapNotNull { it.toDomainOrNull() }
            affected.forEach { entry ->
                dao.deleteWithMutation(
                    date = entry.plannedOn.toString(),
                    slot = entry.slot.id,
                    mutation = outbox.mealPlanDelete(entry.key),
                )
            }
            affected.map { it.key }
        }
}
