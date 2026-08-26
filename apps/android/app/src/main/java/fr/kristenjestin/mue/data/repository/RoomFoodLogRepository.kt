package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.FoodLogDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The journal of PRD_FOOD 10, over Room.
 *
 * There is deliberately no merging, no de-duplication and no "same food, same moment" rule:
 * 21.3 makes every line independent, and 8.1 allows as many heterogeneous lines at one moment as
 * the user wants. The repository therefore writes exactly what it is given, and each write
 * carries its own outbox row in the same transaction (FR-SYNC-001).
 *
 * The nutrients are stored as handed over, snapshot and all. Recomputing them here from the food
 * would undo 8.4's central promise — that editing a food never changes a line already written.
 */
class RoomFoodLogRepository(
    private val dao: FoodLogDao,
    private val outbox: SyncOutbox = SyncOutbox(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : FoodLogRepository {

    override fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>> =
        dao.observeDay(date.toString())
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun observeIn(window: DateWindow): Flow<List<FoodLogEntry>> =
        dao.observeInWindow(window.start?.toString(), window.endInclusive?.toString())
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun observeLoggedDatesIn(window: DateWindow): Flow<List<LocalDate>> =
        dao.observeLoggedDatesInWindow(window.start?.toString(), window.endInclusive?.toString())
            .map { dates -> dates.map(LocalDate::parse) }
            .flowOn(ioDispatcher)

    override suspend fun findById(id: FoodLogEntryId): FoodLogEntry? = withContext(ioDispatcher) {
        dao.findById(id.value)?.toDomain()
    }

    /** The line a proposition produced when it was confirmed (PRD_FOOD 8.5). */
    override suspend fun findByPlan(key: MealPlanKey): FoodLogEntry? = withContext(ioDispatcher) {
        dao.findByPlan(key.plannedOn.toString(), key.slot.id)?.toDomain()
    }

    override suspend fun recentlyUsedFoods(limit: Int): List<FoodId> = withContext(ioDispatcher) {
        dao.recentlyUsedFoods(limit).map(::FoodId)
    }

    override suspend fun save(entry: FoodLogEntry) = withContext(ioDispatcher) {
        val stamp = now()
        dao.upsertWithMutation(
            entity = entry.toEntity(createdAt = stamp, updatedAt = stamp),
            mutation = outbox.foodLogUpsert(entry),
        )
    }

    override suspend fun delete(id: FoodLogEntryId) = withContext(ioDispatcher) {
        dao.deleteWithMutation(id.value, outbox.foodLogDelete(id))
    }
}
